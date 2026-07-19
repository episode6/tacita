package com.episode6.tacita

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.episode6.tacita.AdFingerprintInfo.Provenance
import com.episode6.tacita.audio.encodeMp3
import com.episode6.tacita.audio.synth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test

/**
 * End-to-end coverage of the acoustic fingerprint store: the global/cross-feed layer for
 * Simplecast-class hosts that re-encode every episode (docs/ALGORITHM.md "Store scoping",
 * 2026-07-19). Servings are built with the same in-test jump3r encoding the fingerprinter
 * tests use, so recurrences are *re-encodes* of the creative's audio — the case the byte
 * layer can never match.
 */
class TacitaAcousticTest {

  private val dir = Files.createTempDirectory("tacita-acoustic-test").toFile().apply { deleteOnExit() }
  private val outputFile = dir.resolve("episode.mp3")
  private val referenceFile = dir.resolve("episode.mp3.adref")
  private val storePath = dir.resolve("ads.tacita-afp").toOkioPath()

  private var requestCount = 0

  @Test fun `an acoustic store requires a feedId`() {
    val tacita = Tacita.withClient { HttpClient(engine(emptyList())) }

    assertFailure {
      runBlocking {
        tacita.downloadPodcast(
          URL, outputFile.toOkioPath(), referenceFile.toOkioPath(),
          overwrite = false, cutAds = true, acousticFingerprintStore = storePath,
        ).toList()
      }
    }.isInstanceOf(IllegalArgumentException::class)
  }

  @Test fun `seeds diff-proven acoustic fingerprints from applied cuts and log-matches their re-encoded recurrence`() = runBlocking<Unit> {
    val adPcm = synth(seed = 1, seconds = 8.0)
    val contentEnc = encodeMp3(synth(seed = 2, seconds = 40.0), kbps = 64)
    val adEnc = encodeMp3(adPcm, kbps = 128)
    val logLines = mutableListOf<String>()

    // episode 1: the diff proves the appended break was injected and cuts it -> store seeded
    val engine1 = engine(listOf(contentEnc + adEnc, contentEnc))
    val tacita1 = Tacita.withClient(log = { logLines += it }) { HttpClient(engine1) }
    tacita1.downloadPodcast(
      URL, outputFile.toOkioPath(), referenceFile.toOkioPath(),
      overwrite = false, cutAds = true,
      acousticFingerprintStore = storePath, feedId = FEED_A,
    ).toList()

    assertThat(outputFile.readBytes()).isEqualTo(contentEnc)
    assertThat(logLines.filter { "seeded 1 diff-proven acoustic" in it }).hasSize(1)
    val stored = tacita1.acousticFingerprints(storePath)
    assertThat(stored).hasSize(1)
    assertThat(stored.single().attributions).isEqualTo(mapOf(FEED_A to Provenance.DIFF_PROVEN))

    // episode 2: sticky fill (identical copies) blinds the diff, and the whole serving is a
    // fresh transcode at a different bitrate, sample rate and gain — zero shared bytes with
    // the stored creative's encode. Only the acoustic layer can see it, and it must only log.
    val episode2 = encodeMp3(
      pcm = synth(seed = 3, seconds = 20.0) + adPcm + synth(seed = 4, seconds = 12.0),
      kbps = 64,
      resampleKHz = "22.05",
      scale = 0.7,
    )
    requestCount = 0
    logLines.clear()
    val output2 = dir.resolve("two.mp3")
    val engine2 = engine(listOf(episode2, episode2))
    val tacita2 = Tacita.withClient(log = { logLines += it }) { HttpClient(engine2) }
    val states = tacita2.downloadPodcast(
      URL, output2.toOkioPath(), dir.resolve("two.mp3.adref").toOkioPath(),
      overwrite = false, cutAds = true,
      acousticFingerprintStore = storePath, feedId = FEED_A,
    ).toList()

    assertThat(output2.readBytes(), name = "acoustic matches must never modify the file").isEqualTo(episode2)
    val matchLines = logLines.filter { "acoustic fingerprint ${stored.single().id.take(12)}" in it && "matched" in it }
    assertThat(matchLines, name = "the re-encoded recurrence is reported to the log").hasSize(1)
    assertThat(matchLines.single().contains("$FEED_A=DIFF_PROVEN"), name = "log line carries the attribution").isTrue()
    assertThat(logLines.filter { "acoustic match pass took" in it }, name = "match timing is logged for cost measurement").isNotEmpty()
    val candidates = (states.last() as DownloadState.Complete).adBoundaryCandidates
    assertThat(
      candidates.filter { it.source == AdBoundaryCandidate.Source.FINGERPRINT },
      name = "log-only: acoustic matches emit no candidates",
    ).isEmpty()
  }

  @Test fun `confirmAcousticAd merges attributions across feeds and supports revocation`() = runBlocking<Unit> {
    val adPcm = synth(seed = 1, seconds = 8.0)
    outputFile.writeBytes(encodeMp3(synth(seed = 5, seconds = 10.0) + adPcm + synth(seed = 6, seconds = 10.0), kbps = 64))
    val tacita = Tacita.withClient { HttpClient(engine(emptyList())) }

    // the listener heard the break at ~10s..18s and confirmed it imprecisely
    val info = tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_A, startMs = 9_000, endMs = 19_500)

    assertThat(info.attributions).isEqualTo(mapOf(FEED_A to Provenance.HUMAN_CONFIRMED))
    assertThat(tacita.acousticFingerprints(storePath)).hasSize(1)

    // the same creative confirmed from a second feed merges into one entry
    val merged = tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_B, startMs = 9_000, endMs = 19_500)

    assertThat(merged.id, name = "same decoded audio, same creative").isEqualTo(info.id)
    assertThat(merged.attributions).isEqualTo(mapOf(FEED_A to Provenance.HUMAN_CONFIRMED, FEED_B to Provenance.HUMAN_CONFIRMED))
    assertThat(tacita.acousticFingerprints(storePath)).hasSize(1)

    // and the listener can revoke it — for every feed at once
    assertThat(tacita.removeAcousticFingerprint(storePath, info.id)).isTrue()
    assertThat(tacita.acousticFingerprints(storePath)).isEmpty()
    assertThat(tacita.removeAcousticFingerprint(storePath, info.id), name = "second removal finds nothing").isFalse()
  }

  @Test fun `confirmAcousticAd rejects ranges too short to fingerprint`() = runBlocking<Unit> {
    outputFile.writeBytes(encodeMp3(synth(seed = 5, seconds = 10.0), kbps = 64))
    val tacita = Tacita.withClient { HttpClient(engine(emptyList())) }

    assertFailure {
      tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_A, startMs = 1_000, endMs = 3_000)
    }.isInstanceOf(IllegalArgumentException::class)
  }

  @Test fun `confirmAcousticAd rejects stationary audio that cannot be matched distinctively`() = runBlocking<Unit> {
    // a held tone — the degenerate class the extraction floors exist for: it would
    // "match" any other stationary audio (docs/ALGORITHM.md 2026-07-19)
    val tone = FloatArray(10 * 44100) { (0.3 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000 * it / 44100)).toFloat() }
    outputFile.writeBytes(encodeMp3(tone, kbps = 64))
    val tacita = Tacita.withClient { HttpClient(engine(emptyList())) }

    assertFailure {
      tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_A, startMs = 1_000, endMs = 8_000)
    }.isInstanceOf(IllegalArgumentException::class)
  }

  @Test fun `a verified-clean serving revokes only the observing feed's attribution`() = runBlocking<Unit> {
    val adPcm = synth(seed = 1, seconds = 8.0)
    outputFile.writeBytes(encodeMp3(synth(seed = 5, seconds = 10.0) + adPcm + synth(seed = 6, seconds = 10.0), kbps = 64))
    val logLines = mutableListOf<String>()
    val tacita = Tacita.withClient(log = { logLines += it }) { HttpClient(engine(emptyList())) }
    tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_A, startMs = 9_000, endMs = 19_500)
    val id = tacita.confirmAcousticAd(outputFile.toOkioPath(), storePath, feedId = FEED_B, startMs = 9_000, endMs = 19_500).id

    // feed A's publisher upload contains the same audio (re-encoded): it's content there,
    // but feed B's confirmation must survive
    val clean = encodeMp3(synth(seed = 7, seconds = 6.0) + adPcm + synth(seed = 8, seconds = 6.0), kbps = 64, scale = 0.9)
    val cleanOut = dir.resolve("clean.mp3")
    val tacitaA = Tacita.withClient(log = { logLines += it }) { HttpClient(engine(listOf(clean, clean))) } // one probe, one download
    tacitaA.downloadPodcast(
      URL, cleanOut.toOkioPath(), dir.resolve("clean.mp3.adref").toOkioPath(),
      overwrite = false, cutAds = true,
      declaredEnclosureBytes = clean.size.toLong(),
      acousticFingerprintStore = storePath, feedId = FEED_A,
    ).toList()

    val afterA = tacita.acousticFingerprints(storePath)
    assertThat(afterA, name = "the creative survives feed A's clean serving").hasSize(1)
    assertThat(afterA.single().id).isEqualTo(id)
    assertThat(afterA.single().attributions, name = "only feed A's attribution is revoked").isEqualTo(mapOf(FEED_B to Provenance.HUMAN_CONFIRMED))
    assertThat(logLines.filter { "revoked feed $FEED_A" in it }).hasSize(1)

    // feed B's clean serving removes the last attribution and with it the fingerprint
    requestCount = 0
    val cleanOut2 = dir.resolve("clean2.mp3")
    val tacitaB = Tacita.withClient(log = { logLines += it }) { HttpClient(engine(listOf(clean, clean))) }
    tacitaB.downloadPodcast(
      URL, cleanOut2.toOkioPath(), dir.resolve("clean2.mp3.adref").toOkioPath(),
      overwrite = false, cutAds = true,
      declaredEnclosureBytes = clean.size.toLong(),
      acousticFingerprintStore = storePath, feedId = FEED_B,
    ).toList()

    assertThat(tacita.acousticFingerprints(storePath), name = "no feed's evidence remains").isEmpty()
  }

  private fun engine(responses: List<ByteArray>): MockEngine = MockEngine {
    val body = responses[requestCount++]
    respond(
      content = body,
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
    )
  }
}

private const val URL = "https://example.com/episode.mp3"
private const val FEED_A = "https://feeds.example.com/show-a"
private const val FEED_B = "https://feeds.example.com/show-b"
