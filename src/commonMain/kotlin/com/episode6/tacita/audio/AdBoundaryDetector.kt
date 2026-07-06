package com.episode6.tacita.audio

import com.episode6.tacita.AdBoundaryCandidate
import com.episode6.tacita.AdBoundaryCandidate.Role
import com.episode6.tacita.AdBoundaryCandidate.Source
import com.episode6.tacita.systemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.use

/**
 * The aggressive last-pass ad-boundary detector: gathers every signal that *might* mark an
 * ad start/end in the final output file and surfaces them as [AdBoundaryCandidate]s on
 * [com.episode6.tacita.DownloadState.Complete]. It never modifies the file and never fails
 * the download — each signal is independently guarded and a total failure yields an empty
 * list. Because it cuts nothing it is exempt from the under-cut invariant (see
 * docs/ALGORITHM.md): candidates are unverified guesses and false positives are expected
 * by design.
 *
 * Never loads the whole file: the segment scan streams through a fixed window and the
 * chapter read stops at the ID3v2 tag. Episodes routinely exceed 100MB and a single
 * whole-file ByteArray silently OOMed inside the guards on Android (2026-07-05, see
 * docs/ALGORITHM.md) — [AdCutter] still reads whole files, but only on the diff path.
 */
internal class AdBoundaryDetector(
  private val fileSystem: FileSystem = systemFileSystem,
  private val mp3SegmentParser: Mp3SegmentParser = Mp3SegmentParser(),
  private val id3FrameReader: Id3FrameReader = Id3FrameReader(),
  private val log: (String) -> Unit = {},
) {

  suspend fun detect(
    file: Path,
    /** null when no diff ran (the clean-source path). */
    cutResult: AdCutter.Result?,
    /** From [com.episode6.tacita.http.CleanSourceResolver.Resolution]; original-timeline ms. */
    daiSlotsMs: List<Long>,
  ): List<AdBoundaryCandidate> = withContext(Dispatchers.IO) {
    val raw = mutableListOf<AdBoundaryCandidate>()
    guarded(file, "opening file") { fileSystem.openReadOnly(file) }?.use { handle ->
      raw += guarded(file, "segment scan") { segmentJoins(handle) }.orEmpty()
      raw += guarded(file, "id3 chapters") { chapterEdges(id3TagPrefix(handle)) }.orEmpty()
    }
    raw += guarded(file, "diff mapping") { diffCandidates(cutResult) }.orEmpty()
    raw += daiSlotsMs.map {
      AdBoundaryCandidate(timeMs = it, source = Source.DAI_SLOT, role = Role.JOIN, confidence = CONFIDENCE_DAI_SLOT)
    }
    finish(file, raw)
  }

  /** Joins between independently-encoded segments; the first segment's start is not a join. */
  private fun segmentJoins(handle: FileHandle): List<AdBoundaryCandidate> =
    mp3SegmentParser.scan(handle).segments.zipWithNext().map { (_, next) ->
      AdBoundaryCandidate(
        timeMs = (next.startSeconds * 1000).toLong(),
        source = Source.SEGMENT_BOUNDARY,
        role = Role.JOIN,
        confidence = CONFIDENCE_SEGMENT_BOUNDARY,
      )
    }

  /**
   * The leading ID3v2 tag (header plus declared size) — all [Id3FrameReader] needs, so the
   * audio never has to be in memory. Capped so a corrupt declared size can't allocate
   * unbounded; a truncated read surfaces via the caller's guard, same as an unreadable tag.
   */
  private fun id3TagPrefix(handle: FileHandle): ByteArray {
    val header = ByteArray(ID3_HEADER_BYTES)
    var at = 0
    while (at < header.size) {
      val read = handle.read(at.toLong(), header, at, header.size - at)
      if (read <= 0) return ByteArray(0)
      at += read
    }
    if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
      return header // no tag; Id3FrameReader will reject it the same way it always has
    }
    val total = (ID3_HEADER_BYTES.toLong() + id3Syncsafe(header, 6))
      .coerceAtMost(handle.size())
      .coerceAtMost(MAX_ID3_TAG_BYTES)
      .toInt()
    val prefix = header.copyOf(total)
    var filled = ID3_HEADER_BYTES
    while (filled < total) {
      val read = handle.read(filled.toLong(), prefix, filled, total - filled)
      if (read <= 0) break
      filled += read
    }
    return prefix
  }

  /**
   * Chapters tile the file, so every start plus the final end covers each edge exactly
   * once; a chapter that labels an ad slot starts at the candidate START.
   */
  private fun chapterEdges(data: ByteArray): List<AdBoundaryCandidate> {
    val chapters = id3FrameReader.chapters(data)
    if (chapters.isEmpty()) return emptyList()
    return chapters.map {
      AdBoundaryCandidate(timeMs = it.startMs, source = Source.ID3_CHAPTER, role = Role.START, confidence = CONFIDENCE_ID3_CHAPTER)
    } + AdBoundaryCandidate(
      timeMs = chapters.maxOf { it.endMs },
      source = Source.ID3_CHAPTER,
      role = Role.END,
      confidence = CONFIDENCE_ID3_CHAPTER,
    )
  }

  private fun diffCandidates(cutResult: AdCutter.Result?): List<AdBoundaryCandidate> = when (cutResult) {
    is AdCutter.Result.AdsCut -> {
      // each applied cut collapses to a single splice point in the output timeline
      var removedSeconds = 0.0
      cutResult.cuts.map { cut ->
        val spliceMs = ((cut.fromSeconds - removedSeconds) * 1000).toLong()
        removedSeconds += cut.seconds
        AdBoundaryCandidate(timeMs = spliceMs, source = Source.DIFF_CUT, role = Role.JOIN, confidence = CONFIDENCE_DIFF_APPLIED)
      }
    }
    // the guards refused these cuts, so the ad-shaped ranges are still in the (untouched) file
    is AdCutter.Result.Skipped -> cutResult.cuts.flatMap { cut ->
      listOf(
        AdBoundaryCandidate(timeMs = (cut.fromSeconds * 1000).toLong(), source = Source.DIFF_CUT, role = Role.START, confidence = CONFIDENCE_DIFF_SKIPPED),
        AdBoundaryCandidate(timeMs = (cut.toSeconds * 1000).toLong(), source = Source.DIFF_CUT, role = Role.END, confidence = CONFIDENCE_DIFF_SKIPPED),
      )
    }
    is AdCutter.Result.NoAdsFound, null -> emptyList()
  }

  /**
   * Near-duplicates within a source are merged (earliest wins); candidates from *different*
   * sources are never merged — agreement between signals is corroboration, and it raises
   * each agreeing candidate's confidence (independent-evidence combination: the boosted
   * value is `1 - Π(1 - cᵢ)` over the agreeing candidates). A garbage-scale diff (a
   * wholesale-disagreeing reference) can produce hundreds of ranges, so the list is
   * capped — keeping the highest-confidence candidates, not the earliest.
   */
  private fun finish(file: Path, raw: List<AdBoundaryCandidate>): List<AdBoundaryCandidate> {
    val merged = raw.groupBy { it.source }.values.flatMap { ofSource ->
      val kept = mutableListOf<AdBoundaryCandidate>()
      ofSource.sortedBy { it.timeMs }.forEach { candidate ->
        if (kept.isEmpty() || candidate.timeMs - kept.last().timeMs > MERGE_WINDOW_MS) kept += candidate
      }
      kept
    }
    val boosted = merged.map { candidate ->
      val agreeing = merged.filter {
        it.source != candidate.source && (it.timeMs - candidate.timeMs) in -MERGE_WINDOW_MS..MERGE_WINDOW_MS
      }
      if (agreeing.isEmpty()) return@map candidate
      val disbelief = (agreeing + candidate).fold(1.0) { acc, c -> acc * (1.0 - c.confidence) }
      candidate.copy(confidence = (1.0 - disbelief).toFloat().coerceIn(0f, 1f))
    }
    val capped = if (boosted.size <= MAX_CANDIDATES) {
      boosted
    } else {
      log("AdBoundaryDetector: ${file.name}: truncating ${boosted.size} candidates to $MAX_CANDIDATES")
      boosted.sortedWith(compareByDescending<AdBoundaryCandidate> { it.confidence }.thenBy { it.timeMs })
        .take(MAX_CANDIDATES)
    }
    return capped.sortedWith(compareBy({ it.timeMs }, { it.source.ordinal }))
  }

  // a candidate list is best-effort diagnostics; no signal is worth failing a download over
  private inline fun <T> guarded(file: Path, step: String, block: () -> T): T? = try {
    block()
  } catch (t: Throwable) {
    log("AdBoundaryDetector: ${file.name}: $step failed: ${t.message}")
    null
  }

  private companion object {
    const val MERGE_WINDOW_MS = 250L
    const val MAX_CANDIDATES = 64
    const val ID3_HEADER_BYTES = 10
    // real-world tags top out around 1-2MB (cover art); syncsafe can claim up to 256MB
    const val MAX_ID3_TAG_BYTES = 16L * 1024 * 1024

    // Uncalibrated priors, ordered by evidence strength (docs/ALGORITHM.md): an applied
    // cut proved injected material; a guard-refused range is real byte-diff evidence the
    // cutter declined to act on; a DAI slot is the host's own ad-server placement; a
    // stitch join marks *an* encode boundary (ads and content assembly alike — dead end
    // 1); a chapter edge is usually a content chapter that only sometimes labels an ad.
    const val CONFIDENCE_DIFF_APPLIED = 0.9f
    const val CONFIDENCE_DAI_SLOT = 0.8f
    const val CONFIDENCE_DIFF_SKIPPED = 0.65f
    const val CONFIDENCE_SEGMENT_BOUNDARY = 0.4f
    const val CONFIDENCE_ID3_CHAPTER = 0.3f
  }
}
