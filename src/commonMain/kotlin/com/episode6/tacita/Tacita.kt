package com.episode6.tacita

import com.episode6.tacita.audio.AdBoundaryDetector
import com.episode6.tacita.audio.AdCutter
import com.episode6.tacita.audio.AdFingerprintStoreFile
import com.episode6.tacita.audio.AdFingerprinter
import com.episode6.tacita.audio.Id3ChapterShifter
import com.episode6.tacita.audio.Mp3SegmentParser
import com.episode6.tacita.audio.StoredAdFingerprint
import com.episode6.tacita.http.CleanSourceResolver
import com.episode6.tacita.http.Downloader
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

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
  /**
   * How strongly the evidence suggests a real ad boundary, in `0.0..1.0`. Values are
   * **uncalibrated heuristic priors** (see docs/ALGORITHM.md): the *ordering* is
   * meaningful — an applied diff cut outranks a leaked DAI slot outranks a chapter
   * edge, and candidates corroborated by a second signal within 250ms rank above
   * uncorroborated ones — but the absolute numbers carry no probability semantics and
   * no ear-verified calibration. Useful for sorting or thresholding a skip list; never
   * a license to auto-cut or auto-skip, at any value.
   */
  public val confidence: Float,
) {

  init {
    require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
  }

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

    /**
     * A byte-exact recurrence of a creative from the caller's fingerprint store — an ad
     * that was previously diff-proven or human-confirmed (see [Tacita.confirmAd]) and is
     * still present in this output file (e.g. sticky fill that blinded the diff).
     * Emitted as START/END pairs spanning the matched creative. The strongest candidate
     * signal there is, but still a candidate: matches never cut (see docs/ALGORITHM.md).
     */
    FINGERPRINT,
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
 * A creative in a fingerprint store (see [Tacita.downloadPodcast]'s `fingerprintStore`
 * param and [Tacita.confirmAd]).
 */
public data class AdFingerprintInfo(
  /** Content-derived identity: the same creative bytes always produce the same id. */
  public val id: String,
  public val provenance: Provenance,
  /** Playback duration of the fingerprinted range. */
  public val durationMs: Long,
  /** Encoded size of the fingerprinted range. */
  public val sizeBytes: Long,
) {

  /** How a fingerprint earned its place in the store. */
  public enum class Provenance {
    /**
     * Auto-seeded from an applied diff cut: the two-copy diff proved these bytes were
     * injected into one serving and the cutter removed them.
     */
    DIFF_PROVEN,

    /**
     * A human listened to the range and confirmed it is an ad (via [Tacita.confirmAd]) —
     * the strongest evidence this library recognizes. Never downgraded by a later
     * DIFF_PROVEN sighting of the same creative.
     */
    HUMAN_CONFIRMED,
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
   * When [fingerprintStore] is provided (and [cutAds] is true), tacita additionally maintains
   * a store of known ad-creative fingerprints at that path (creating it on first use):
   * every applied diff cut auto-seeds a [AdFingerprintInfo.Provenance.DIFF_PROVEN]
   * fingerprint of the removed bytes, recurrences of stored creatives still present in the
   * output file surface as [AdBoundaryCandidate.Source.FINGERPRINT] candidates (matches
   * never cut the file), and fingerprints that match a verified-clean serving are pruned —
   * a stored "creative" that appears in the publisher's own upload is show content, not an
   * ad. Keep one store per feed: creatives are targeted per show, and per-show scoping
   * avoids false positives from assets shared across a network's shows. Store failures are
   * logged and never fail the download.
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
    fingerprintStore: Path? = null,
  ): Flow<DownloadState>

  /**
   * Records a human-confirmed ad: fingerprints the creative spanning `[startMs, endMs]` of
   * [file]'s playback timeline (a file previously produced by [downloadPodcast]) and adds
   * it to [fingerprintStore] with [AdFingerprintInfo.Provenance.HUMAN_CONFIRMED], creating
   * the store on first use. Future [downloadPodcast] calls passing the same store surface
   * recurrences of the creative as [AdBoundaryCandidate.Source.FINGERPRINT] candidates.
   *
   * The range should be the listener's best estimate of the ad's extent. Edges are snapped
   * to mp3 frame boundaries and imprecision is tolerated: bytes unique to this episode that
   * leak into the range simply never match again, while the creative's interior still
   * matches in future episodes. Matching is byte-exact, so a confirmation only helps for as
   * long as the host serves the same encoding of the creative (see docs/ALGORITHM.md).
   *
   * Returns the stored fingerprint's info. If the same creative was already stored, its
   * provenance is upgraded to HUMAN_CONFIRMED.
   *
   * @throws IllegalArgumentException when the range is invalid, shorter than ~5 seconds,
   *   longer than ~10 minutes, or outside the file's audio
   * @throws okio.IOException when [file] can't be read or the store can't be written
   */
  public suspend fun confirmAd(
    file: Path,
    fingerprintStore: Path,
    startMs: Long,
    endMs: Long,
  ): AdFingerprintInfo

  /** The fingerprints currently in [fingerprintStore] (empty when the store doesn't exist). */
  public suspend fun fingerprints(fingerprintStore: Path): List<AdFingerprintInfo>

  /**
   * Revokes a fingerprint (e.g. one the listener decides was confirmed in error) by its
   * [AdFingerprintInfo.id]. Returns false when no such fingerprint exists in the store.
   */
  public suspend fun removeFingerprint(fingerprintStore: Path, id: String): Boolean

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
  private val mp3SegmentParser: Mp3SegmentParser = Mp3SegmentParser(),
  id3ChapterShifter: Id3ChapterShifter = Id3ChapterShifter(),
) : Tacita {

  private val reusedClient: HttpClient by lazy(httpClientFactory)
  private val adFingerprinter = AdFingerprinter()
  private val fingerprintStoreFile = AdFingerprintStoreFile(fileSystem)
  private val adCutter = AdCutter(
    fileSystem = fileSystem,
    log = log,
    mp3SegmentParser = mp3SegmentParser,
    id3ChapterShifter = id3ChapterShifter,
    adFingerprinter = adFingerprinter,
  )
  private val adBoundaryDetector = AdBoundaryDetector(
    fileSystem = fileSystem,
    mp3SegmentParser = mp3SegmentParser,
    adFingerprinter = adFingerprinter,
    fingerprintStoreFile = fingerprintStoreFile,
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
    fingerprintStore: Path?,
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
            // a stored "creative" that appears in the publisher's own upload is show
            // content a human mis-confirmed (or a hash accident) — never keep it
            if (fingerprintStore != null) pruneFingerprintsMatchingCleanServing(outputFile, fingerprintStore)
            emit(
              DownloadState.Complete(
                adBoundaryDetector.detect(outputFile, cutResult = null, daiSlotsMs = daiSlotsMs, fingerprintStore = fingerprintStore),
              ),
            )
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
        val cutResult = adCutter.cutAds(file = outputFile, referenceFile = referenceFile, seedFingerprints = fingerprintStore != null)
        if (fingerprintStore != null && cutResult is AdCutter.Result.AdsCut) {
          seedFingerprints(outputFile, fingerprintStore, cutResult.fingerprints)
        }
        adBoundaryCandidates = adBoundaryDetector.detect(outputFile, cutResult = cutResult, daiSlotsMs = daiSlotsMs, fingerprintStore = fingerprintStore)
      }
      emit(DownloadState.Complete(adBoundaryCandidates))
    } finally {
      if (!reuseClient) httpClient.close()
    }
  }

  override suspend fun confirmAd(
    file: Path,
    fingerprintStore: Path,
    startMs: Long,
    endMs: Long,
  ): AdFingerprintInfo = withContext(Dispatchers.IO) {
    require(startMs >= 0) { "startMs must not be negative, was $startMs" }
    require(endMs > startMs) { "endMs ($endMs) must be greater than startMs ($startMs)" }
    val fingerprint = fileSystem.openReadOnly(file).use { handle ->
      val snapped = mp3SegmentParser.byteRangeForMs(handle, startMs, endMs)
        ?: throw IllegalArgumentException("range ${startMs}ms..${endMs}ms is outside the audio of $file")
      val durationSeconds = snapped.toSeconds - snapped.fromSeconds
      require(durationSeconds >= AdFingerprinter.MIN_FINGERPRINT_SECONDS) {
        "range is too short to fingerprint (${durationSeconds}s < ${AdFingerprinter.MIN_FINGERPRINT_SECONDS}s)"
      }
      require(durationSeconds <= AdFingerprinter.MAX_FINGERPRINT_SECONDS) {
        "range is too long to fingerprint (${durationSeconds}s > ${AdFingerprinter.MAX_FINGERPRINT_SECONDS}s)"
      }
      val bytes = ByteArray(snapped.toByte - snapped.fromByte)
      var filled = 0
      while (filled < bytes.size) {
        val read = handle.read((snapped.fromByte + filled).toLong(), bytes, filled, bytes.size - filled)
        if (read <= 0) throw IOException("unexpected end of file reading $file at byte ${snapped.fromByte + filled}")
        filled += read
      }
      adFingerprinter.extract(
        data = bytes,
        fromByte = 0,
        toByte = bytes.size,
        durationSeconds = durationSeconds,
        provenance = AdFingerprintInfo.Provenance.HUMAN_CONFIRMED,
      ) ?: throw IllegalArgumentException("range ${startMs}ms..${endMs}ms is too small to fingerprint")
    }
    fingerprintStoreFile.add(fingerprintStore, listOf(fingerprint))
    log("Tacita: ${file.name}: human-confirmed ad fingerprint ${fingerprint.id.take(12)} (${fingerprint.durationMs / 1000}s)")
    fingerprint.info
  }

  override suspend fun fingerprints(fingerprintStore: Path): List<AdFingerprintInfo> = withContext(Dispatchers.IO) {
    fingerprintStoreFile.read(fingerprintStore).map { it.info }
  }

  override suspend fun removeFingerprint(fingerprintStore: Path, id: String): Boolean = withContext(Dispatchers.IO) {
    fingerprintStoreFile.remove(fingerprintStore, id)
  }

  // store maintenance never fails a download: it's an accuracy improvement, not a dependency
  private fun seedFingerprints(outputFile: Path, store: Path, fingerprints: List<StoredAdFingerprint>) {
    if (fingerprints.isEmpty()) return
    try {
      val added = fingerprintStoreFile.add(store, fingerprints)
      if (added > 0) log("Tacita: ${outputFile.name}: seeded $added diff-proven ad fingerprint(s)")
    } catch (t: Throwable) {
      log("Tacita: ${outputFile.name}: fingerprint seeding failed: ${t.message}")
    }
  }

  private fun pruneFingerprintsMatchingCleanServing(file: Path, store: Path) {
    try {
      val stored = fingerprintStoreFile.read(store)
      if (stored.isEmpty()) return
      val matches = fileSystem.openReadOnly(file).use { adFingerprinter.match(it, stored) }
      if (matches.isEmpty()) return
      val ids = matches.map { it.fingerprint.id }.toSet()
      fingerprintStoreFile.write(store, stored.filterNot { it.id in ids })
      log("Tacita: ${file.name}: pruned ${ids.size} fingerprint(s) that matched the verified-clean serving (content, not ads)")
    } catch (t: Throwable) {
      log("Tacita: ${file.name}: clean-serving fingerprint prune failed: ${t.message}")
    }
  }
}
