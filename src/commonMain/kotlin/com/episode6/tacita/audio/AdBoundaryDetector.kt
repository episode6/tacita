package com.episode6.tacita.audio

import com.episode6.tacita.AdBoundaryCandidate
import com.episode6.tacita.AdBoundaryCandidate.Role
import com.episode6.tacita.AdBoundaryCandidate.Source
import com.episode6.tacita.systemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * The aggressive last-pass ad-boundary detector: gathers every signal that *might* mark an
 * ad start/end in the final output file and surfaces them as [AdBoundaryCandidate]s on
 * [com.episode6.tacita.DownloadState.Complete]. It never modifies the file and never fails
 * the download — each signal is independently guarded and a total failure yields an empty
 * list. Because it cuts nothing it is exempt from the under-cut invariant (see
 * docs/ALGORITHM.md): candidates are unverified guesses and false positives are expected
 * by design.
 *
 * Reads the whole output file into memory once (same pattern and Int-offset size limit as
 * [AdCutter]); on the clean-source path this is the file's first full read.
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
    val data = guarded(file, "reading file") { fileSystem.read(file) { readByteArray() } }
    if (data != null) {
      raw += guarded(file, "segment scan") { segmentJoins(data) }.orEmpty()
      raw += guarded(file, "id3 chapters") { chapterEdges(data) }.orEmpty()
    }
    raw += guarded(file, "diff mapping") { diffCandidates(cutResult) }.orEmpty()
    raw += daiSlotsMs.map { AdBoundaryCandidate(timeMs = it, source = Source.DAI_SLOT, role = Role.JOIN) }
    finish(file, raw)
  }

  /** Joins between independently-encoded segments; the first segment's start is not a join. */
  private fun segmentJoins(data: ByteArray): List<AdBoundaryCandidate> =
    mp3SegmentParser.scan(data).segments.zipWithNext().map { (_, next) ->
      AdBoundaryCandidate(
        timeMs = (next.startSeconds * 1000).toLong(),
        source = Source.SEGMENT_BOUNDARY,
        role = Role.JOIN,
      )
    }

  /**
   * Chapters tile the file, so every start plus the final end covers each edge exactly
   * once; a chapter that labels an ad slot starts at the candidate START.
   */
  private fun chapterEdges(data: ByteArray): List<AdBoundaryCandidate> {
    val chapters = id3FrameReader.chapters(data)
    if (chapters.isEmpty()) return emptyList()
    return chapters.map { AdBoundaryCandidate(timeMs = it.startMs, source = Source.ID3_CHAPTER, role = Role.START) } +
      AdBoundaryCandidate(timeMs = chapters.maxOf { it.endMs }, source = Source.ID3_CHAPTER, role = Role.END)
  }

  private fun diffCandidates(cutResult: AdCutter.Result?): List<AdBoundaryCandidate> = when (cutResult) {
    is AdCutter.Result.AdsCut -> {
      // each applied cut collapses to a single splice point in the output timeline
      var removedSeconds = 0.0
      cutResult.cuts.map { cut ->
        val spliceMs = ((cut.fromSeconds - removedSeconds) * 1000).toLong()
        removedSeconds += cut.seconds
        AdBoundaryCandidate(timeMs = spliceMs, source = Source.DIFF_CUT, role = Role.JOIN)
      }
    }
    // the guards refused these cuts, so the ad-shaped ranges are still in the (untouched) file
    is AdCutter.Result.Skipped -> cutResult.cuts.flatMap { cut ->
      listOf(
        AdBoundaryCandidate(timeMs = (cut.fromSeconds * 1000).toLong(), source = Source.DIFF_CUT, role = Role.START),
        AdBoundaryCandidate(timeMs = (cut.toSeconds * 1000).toLong(), source = Source.DIFF_CUT, role = Role.END),
      )
    }
    is AdCutter.Result.NoAdsFound, null -> emptyList()
  }

  /**
   * Near-duplicates within a source are merged (earliest wins); candidates from *different*
   * sources are never merged — agreement between signals is corroboration the consumer
   * wants to see. A garbage-scale diff (a wholesale-disagreeing reference) can produce
   * hundreds of ranges, so the list is capped: aggressive, not unbounded.
   */
  private fun finish(file: Path, raw: List<AdBoundaryCandidate>): List<AdBoundaryCandidate> {
    val merged = raw.groupBy { it.source }.values.flatMap { ofSource ->
      val kept = mutableListOf<AdBoundaryCandidate>()
      ofSource.sortedBy { it.timeMs }.forEach { candidate ->
        if (kept.isEmpty() || candidate.timeMs - kept.last().timeMs > MERGE_WINDOW_MS) kept += candidate
      }
      kept
    }
    val sorted = merged.sortedWith(compareBy({ it.timeMs }, { it.source.ordinal }))
    if (sorted.size <= MAX_CANDIDATES) return sorted
    log("AdBoundaryDetector: ${file.name}: truncating ${sorted.size} candidates to $MAX_CANDIDATES")
    return sorted.take(MAX_CANDIDATES)
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
  }
}
