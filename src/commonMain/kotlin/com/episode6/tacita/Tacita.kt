package com.episode6.tacita

import com.episode6.tacita.audio.AdBoundaryDetector
import com.episode6.tacita.audio.AdCutter
import com.episode6.tacita.audio.Id3ChapterShifter
import com.episode6.tacita.audio.Mp3SegmentParser
import com.episode6.tacita.http.CleanSourceResolver
import com.episode6.tacita.http.Downloader
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.FileSystem
import okio.IOException
import okio.Path

/** Thrown by [Tacita.downloadPodcast] when the output file exists and `overwrite` is false. */
public class FileAlreadyExistsException(path: Path) : IOException("file already exists: $path")

/** Where a [Tacita.downloadPodcast] call currently is. */
public sealed class DownloadState {
  /** [file] is being downloaded and is [percentComplete] (`0f`..`1f`) finished. */
  public data class Downloading(public val file: Path, public val percentComplete: Float) : DownloadState()

  /** Both copies are on disk and the injected ads are being diffed out. */
  public data object CuttingAds : DownloadState()

  /**
   * The episode is fully downloaded (and de-ad'd, if requested).
   *
   * [adBoundaryCandidates] are aggressive, unverified guesses at ad boundaries that remain
   * in the output file (always empty when `cutAds` was false). Render them as skippable
   * chapter markers only — never auto-cut or auto-skip on them; false positives are
   * expected by design (see [AdBoundaryCandidate]).
   */
  public data class Complete(
    public val adBoundaryCandidates: List<AdBoundaryCandidate> = emptyList(),
  ) : DownloadState()
}

/**
 * A point in the final output file's timeline that *might* be the start or end of an ad.
 *
 * Candidates are the aggressive counterpart to the conservative ad-cutter: they surface
 * every ad-shaped signal the pipeline saw — including the ones the cutter's guards refused
 * to act on — without ever modifying the file. They are UNVERIFIED guesses and false
 * positives are expected by design: byte-shaped evidence about ads is unreliable (see
 * docs/ALGORITHM.md), so render candidates as skippable chapter markers only and never
 * auto-cut or auto-skip on them.
 */
public data class AdBoundaryCandidate(
  /** Position in the final output file's timeline, in milliseconds. */
  public val timeMs: Long,
  public val source: Source,
  public val role: Role,
) {

  /** Which detection signal produced a candidate. */
  public enum class Source {
    /** A join between independently-encoded mp3 segments in the output file. */
    SEGMENT_BOUNDARY,

    /**
     * From diffing against the reference copy: a splice point where an ad was cut out, or
     * an edge of an ad-shaped range the cutter's guards refused to cut.
     */
    DIFF_CUT,

    /**
     * A dynamic-ad-insertion slot position leaked by the host's redirect chain. Times are
     * in the host's original (clean) timeline; after a diff-cut they are close
     * approximations of the output timeline rather than exact positions.
     */
    DAI_SLOT,

    /** An ID3v2 CHAP frame edge written by the host (Audioboom labels ad slots this way). */
    ID3_CHAPTER,
  }

  /** How a candidate's [timeMs] relates to a possible ad. */
  public enum class Role {
    /** A possible ad may begin here. */
    START,

    /** A possible ad may end here. */
    END,

    /** Material was (or may have been) joined here; either side could be an ad. */
    JOIN,
  }
}

/**
 * Downloads podcast episodes and cuts the dynamically-injected ads out of them.
 *
 * The companion object is itself a [Tacita] backed by ktor's default engine discovery, so simple
 * callers can use `Tacita.downloadPodcast(...)` directly. Callers that need a custom http client
 * (engine config, proxies, tests via ktor's MockEngine) can hold a [Tacita.withClient] instance
 * instead — e.g. as a DI singleton.
 */
public interface Tacita {

  /**
   * Downloads the podcast episode at [url] to [outputFile], optionally cutting
   * dynamically-injected ads out of it. Does nothing until collected.
   *
   * When the feed's declared episode size ([declaredEnclosureBytes], the `enclosure length`
   * attribute) and/or duration ([expectedDurationSeconds], `itunes:duration`) are provided and
   * [cutAds] is true, tacita first probes for a serving that provably contains no injected ads —
   * the pinned-tier serving itself, a static `fallback_url` leaked in the host's redirect chain,
   * or a bot-tier serving — and downloads that copy directly, skipping the diff. Some hosts
   * inject sticky fill on every tier, which blinds a same-session reference; a verified-clean
   * serving is the only reliable escape, so callers that have feed metadata should pass it.
   *
   * Otherwise ads are identified by diffing against a second copy of the same episode (kept at
   * [referenceFile]): dynamically injected fill varies per request while everything the episode
   * always ships with doesn't, so byte runs unique to [outputFile] are the injected ads. The
   * splice is lossless (no re-encoding) and the failure mode is always keeping too much, never
   * cutting material that belongs to the episode.
   *
   * Back-to-back requests tend to receive identical fill, so a copy from an earlier session
   * makes a far better reference than a fresh one. To that end: when [overwrite]-ing an existing
   * [outputFile] it is promoted to become the reference, an existing [referenceFile] is reused
   * instead of re-downloaded, and reference files are kept on disk for future runs.
   *
   * When [cutAds] is true, the terminal [DownloadState.Complete] also carries
   * [AdBoundaryCandidate]s — aggressive, unverified guesses at ad boundaries remaining in the
   * output file, gathered by a read-only pass that never modifies the file (and never fails
   * the download). See [AdBoundaryCandidate] for the consumer contract.
   *
   * The flow throws [FileAlreadyExistsException] if [outputFile] exists and [overwrite] is false.
   */
  public fun downloadPodcast(
    url: String,
    outputFile: Path,
    referenceFile: Path,
    overwrite: Boolean,
    cutAds: Boolean,
    declaredEnclosureBytes: Long? = null,
    expectedDurationSeconds: Long? = null,
  ): Flow<DownloadState>

  public companion object : Tacita by TacitaImpl() {
    /**
     * Returns a [Tacita] whose downloads use http clients from [factory].
     *
     * When [reuse] is false (the default), [factory] is invoked once per download and MUST
     * return a new [HttpClient] each time — tacita owns and closes it when the download
     * completes. (Passing a pre-built engine, e.g. ktor's MockEngine, is fine: closing a client
     * doesn't close an externally-supplied engine.)
     *
     * When [reuse] is true, [factory] is invoked lazily once, the resulting client is shared by
     * every download, and tacita NEVER closes it — the caller owns its lifecycle.
     *
     * [log] receives diagnostic log lines (one per ad-cut pass reporting its outcome, plus
     * clean-source-resolution and ad-boundary-detection diagnostics); it defaults to
     * discarding them.
     */
    public fun withClient(
      reuse: Boolean = false,
      log: (String) -> Unit = {},
      factory: () -> HttpClient,
    ): Tacita = TacitaImpl(httpClientFactory = factory, reuseClient = reuse, log = log)

    /**
     * Returns a [Tacita] backed by the same default http-client factory as the companion
     * instance, but with [log] receiving diagnostic log lines (one per ad-cut pass reporting
     * its outcome, plus clean-source-resolution and ad-boundary-detection diagnostics).
     */
    public fun withLogger(log: (String) -> Unit): Tacita = TacitaImpl(log = log)
  }
}

private class TacitaImpl(
  private val httpClientFactory: () -> HttpClient = { HttpClient() },
  private val reuseClient: Boolean = false,
  private val fileSystem: FileSystem = systemFileSystem,
  private val log: (String) -> Unit = {},
  mp3SegmentParser: Mp3SegmentParser = Mp3SegmentParser(),
  id3ChapterShifter: Id3ChapterShifter = Id3ChapterShifter(),
) : Tacita {

  private val reusedClient: HttpClient by lazy(httpClientFactory)
  private val adCutter = AdCutter(
    fileSystem = fileSystem,
    log = log,
    mp3SegmentParser = mp3SegmentParser,
    id3ChapterShifter = id3ChapterShifter,
  )
  private val adBoundaryDetector = AdBoundaryDetector(
    fileSystem = fileSystem,
    mp3SegmentParser = mp3SegmentParser,
    log = log,
  )

  override fun downloadPodcast(
    url: String,
    outputFile: Path,
    referenceFile: Path,
    overwrite: Boolean,
    cutAds: Boolean,
    declaredEnclosureBytes: Long?,
    expectedDurationSeconds: Long?,
  ): Flow<DownloadState> = flow {
    val httpClient = if (reuseClient) reusedClient else httpClientFactory()
    try {
      val downloader = Downloader(httpClient = httpClient, fileSystem = fileSystem)
      var daiSlotsMs: List<Long> = emptyList()

      if (cutAds && !(fileSystem.exists(outputFile) && !overwrite)) {
        val resolution = CleanSourceResolver(downloader = downloader, log = log)
          .resolve(url, declaredEnclosureBytes, expectedDurationSeconds)
        daiSlotsMs = resolution.daiSlotsMs
        val clean = resolution.cleanSource
        if (clean != null) {
          downloader.downloadFile(url = clean.url, outputFile = outputFile, overwrite = overwrite, userAgent = clean.userAgent)
            .collect { emit(DownloadState.Downloading(outputFile, it)) }
          val size = fileSystem.metadata(outputFile).size
          if (size == clean.contentLength) {
            log("Tacita: ${outputFile.name}: clean serving downloaded directly ($size bytes), no ad-cut needed")
            emit(DownloadState.Complete(adBoundaryDetector.detect(outputFile, cutResult = null, daiSlotsMs = daiSlotsMs)))
            return@flow
          }
          // a short read looks like a completed download; never serve a copy we can't
          // verify — fall back to the diff pipeline below
          log("Tacita: ${outputFile.name}: clean serving size mismatch (expected ${clean.contentLength}, got $size); falling back to diff")
          fileSystem.delete(outputFile)
        }
      }

      if (cutAds && overwrite && fileSystem.exists(outputFile)) {
        referenceFile.parent?.let { fileSystem.createDirectories(it) }
        // atomicMove onto an existing file replaces on posix but can throw on windows
        fileSystem.delete(referenceFile, mustExist = false)
        fileSystem.atomicMove(outputFile, referenceFile)
      }
      downloader.downloadFile(url = url, outputFile = outputFile, overwrite = overwrite)
        .collect { emit(DownloadState.Downloading(outputFile, it)) }
      var adBoundaryCandidates: List<AdBoundaryCandidate> = emptyList()
      if (cutAds) {
        if (!fileSystem.exists(referenceFile)) {
          downloader.downloadFile(url = url, outputFile = referenceFile, overwrite = true)
            .collect { emit(DownloadState.Downloading(referenceFile, it)) }
        }
        emit(DownloadState.CuttingAds)
        val cutResult = adCutter.cutAds(file = outputFile, referenceFile = referenceFile)
        adBoundaryCandidates = adBoundaryDetector.detect(outputFile, cutResult = cutResult, daiSlotsMs = daiSlotsMs)
      }
      emit(DownloadState.Complete(adBoundaryCandidates))
    } finally {
      if (!reuseClient) httpClient.close()
    }
  }
}
