package com.episode6.tacita

import com.episode6.tacita.audio.AdCutter
import com.episode6.tacita.audio.Id3ChapterShifter
import com.episode6.tacita.audio.Mp3SegmentParser
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
   * Ads are identified by diffing against a second copy of the same episode (kept at
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
     */
    public fun withClient(reuse: Boolean = false, factory: () -> HttpClient): Tacita =
      TacitaImpl(httpClientFactory = factory, reuseClient = reuse)
  }
}

private class TacitaImpl(
  private val httpClientFactory: () -> HttpClient = { HttpClient() },
  private val reuseClient: Boolean = false,
  private val fileSystem: FileSystem = systemFileSystem,
  mp3SegmentParser: Mp3SegmentParser = Mp3SegmentParser(),
  id3ChapterShifter: Id3ChapterShifter = Id3ChapterShifter(),
) : Tacita {

  private val reusedClient: HttpClient by lazy(httpClientFactory)
  private val adCutter = AdCutter(
    fileSystem = fileSystem,
    mp3SegmentParser = mp3SegmentParser,
    id3ChapterShifter = id3ChapterShifter,
  )

  override fun downloadPodcast(
    url: String,
    outputFile: Path,
    referenceFile: Path,
    overwrite: Boolean,
    cutAds: Boolean,
  ): Flow<DownloadState> = flow {
    val httpClient = if (reuseClient) reusedClient else httpClientFactory()
    try {
      val downloader = Downloader(httpClient = httpClient, fileSystem = fileSystem)
      if (cutAds && overwrite && fileSystem.exists(outputFile)) {
        referenceFile.parent?.let { fileSystem.createDirectories(it) }
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
