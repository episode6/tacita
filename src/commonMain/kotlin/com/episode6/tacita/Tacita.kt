package com.episode6.tacita

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

  /** The episode is fully downloaded (and de-ad'd, if requested). */
  public data object Complete : DownloadState()
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
     * [log] receives diagnostic log lines (currently one per ad-cut pass, reporting its
     * outcome); it defaults to discarding them.
     */
    public fun withClient(
      reuse: Boolean = false,
      log: (String) -> Unit = {},
      factory: () -> HttpClient,
    ): Tacita = TacitaImpl(httpClientFactory = factory, reuseClient = reuse, log = log)

    /**
     * Returns a [Tacita] backed by the same default http-client factory as the companion
     * instance, but with [log] receiving diagnostic log lines (currently one per ad-cut pass,
     * reporting its outcome).
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

      if (cutAds && !(fileSystem.exists(outputFile) && !overwrite)) {
        val clean = CleanSourceResolver(downloader = downloader, log = log)
          .resolve(url, declaredEnclosureBytes, expectedDurationSeconds)
        if (clean != null) {
          downloader.downloadFile(url = clean.url, outputFile = outputFile, overwrite = overwrite, userAgent = clean.userAgent)
            .collect { emit(DownloadState.Downloading(outputFile, it)) }
          val size = fileSystem.metadata(outputFile).size
          if (size == clean.contentLength) {
            log("Tacita: ${outputFile.name}: clean serving downloaded directly ($size bytes), no ad-cut needed")
            emit(DownloadState.Complete)
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
      if (cutAds) {
        if (!fileSystem.exists(referenceFile)) {
          downloader.downloadFile(url = url, outputFile = referenceFile, overwrite = true)
            .collect { emit(DownloadState.Downloading(referenceFile, it)) }
        }
        emit(DownloadState.CuttingAds)
        adCutter.cutAds(file = outputFile, referenceFile = referenceFile)
      }
      emit(DownloadState.Complete)
    } finally {
      if (!reuseClient) httpClient.close()
    }
  }
}
