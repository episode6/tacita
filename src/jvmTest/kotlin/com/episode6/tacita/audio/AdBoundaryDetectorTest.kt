package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.tacita.AdBoundaryCandidate
import com.episode6.tacita.AdBoundaryCandidate.Role
import com.episode6.tacita.AdBoundaryCandidate.Source
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test

class AdBoundaryDetectorTest {

  private val logLines = mutableListOf<String>()
  private val detector = AdBoundaryDetector(log = { logLines += it })

  private val contentA = fixture("content-a.mp3") // ~6.1s
  private val contentB = fixture("content-b.mp3") // ~8.1s

  @Test fun `reports the join between stitched segments`() = runBlocking {
    val file = tempFile(contentA + contentB)

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = emptyList())

    val join = candidates.single { it.source == Source.SEGMENT_BOUNDARY }
    assertThat(join.role).isEqualTo(Role.JOIN)
    assertThat(join.timeMs).isBetween(5_600L, 6_600L) // contentA is ~6.1s
  }

  @Test fun `a single-encode file has no segment joins`() = runBlocking {
    val file = tempFile(contentA)

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = emptyList())

    assertThat(candidates.filter { it.source == Source.SEGMENT_BOUNDARY }).isEmpty()
  }

  @Test fun `applied cuts collapse to splice points in the output timeline`() = runBlocking {
    val file = tempFile(contentA)
    val result = AdCutter.Result.AdsCut(
      adBreaksRemoved = 2,
      secondsRemoved = 15.0,
      cuts = listOf(cut(fromSeconds = 5.0, toSeconds = 10.0), cut(fromSeconds = 20.0, toSeconds = 30.0)),
    )

    val candidates = detector.detect(file.toOkioPath(), cutResult = result, daiSlotsMs = emptyList())

    // the second splice shifts back by the 5s already removed before it
    assertThat(candidates.filter { it.source == Source.DIFF_CUT }).containsExactly(
      AdBoundaryCandidate(timeMs = 5_000, source = Source.DIFF_CUT, role = Role.JOIN),
      AdBoundaryCandidate(timeMs = 15_000, source = Source.DIFF_CUT, role = Role.JOIN),
    )
  }

  @Test fun `guard-refused cuts map to start and end candidates in the untouched file`() = runBlocking {
    val file = tempFile(contentA)
    val result = AdCutter.Result.Skipped("too much", cuts = listOf(cut(fromSeconds = 6.5, toSeconds = 8.25)))

    val candidates = detector.detect(file.toOkioPath(), cutResult = result, daiSlotsMs = emptyList())

    assertThat(candidates.filter { it.source == Source.DIFF_CUT }).containsExactly(
      AdBoundaryCandidate(timeMs = 6_500, source = Source.DIFF_CUT, role = Role.START),
      AdBoundaryCandidate(timeMs = 8_250, source = Source.DIFF_CUT, role = Role.END),
    )
  }

  @Test fun `a NoAdsFound diff contributes nothing`() = runBlocking {
    val file = tempFile(contentA)

    val candidates = detector.detect(file.toOkioPath(), cutResult = AdCutter.Result.NoAdsFound, daiSlotsMs = emptyList())

    assertThat(candidates.filter { it.source == Source.DIFF_CUT }).isEmpty()
  }

  @Test fun `id3 chapters yield every start plus the final end`() = runBlocking {
    val id3 = buildId3(chapter("ch0", fromMs = 0, toMs = 5_000), chapter("ch1", fromMs = 5_000, toMs = 14_000))
    val file = tempFile(id3 + contentA)

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = emptyList())

    assertThat(candidates.filter { it.source == Source.ID3_CHAPTER }).containsExactly(
      AdBoundaryCandidate(timeMs = 0, source = Source.ID3_CHAPTER, role = Role.START),
      AdBoundaryCandidate(timeMs = 5_000, source = Source.ID3_CHAPTER, role = Role.START),
      AdBoundaryCandidate(timeMs = 14_000, source = Source.ID3_CHAPTER, role = Role.END),
    )
  }

  @Test fun `dai slots pass through as join candidates`() = runBlocking {
    val file = tempFile(contentA)

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = listOf(1_000L, 2_000L))

    assertThat(candidates.filter { it.source == Source.DAI_SLOT }).containsExactly(
      AdBoundaryCandidate(timeMs = 1_000, source = Source.DAI_SLOT, role = Role.JOIN),
      AdBoundaryCandidate(timeMs = 2_000, source = Source.DAI_SLOT, role = Role.JOIN),
    )
  }

  @Test fun `near-duplicates within a source merge to the earliest`() = runBlocking {
    val file = tempFile(contentA)

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = listOf(1_000L, 1_100L, 2_000L))

    assertThat(candidates.filter { it.source == Source.DAI_SLOT }.map { it.timeMs }).containsExactly(1_000L, 2_000L)
  }

  @Test fun `agreeing candidates from different sources are both kept`() = runBlocking {
    val file = tempFile(contentA + contentB)
    val segmentJoinMs = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = emptyList())
      .single { it.source == Source.SEGMENT_BOUNDARY }.timeMs

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = listOf(segmentJoinMs))

    assertThat(candidates.filter { it.source == Source.SEGMENT_BOUNDARY }).hasSize(1)
    assertThat(candidates.filter { it.source == Source.DAI_SLOT }).hasSize(1)
  }

  @Test fun `candidates are sorted by time across sources`() = runBlocking {
    val file = tempFile(contentA + contentB)
    val result = AdCutter.Result.Skipped("too much", cuts = listOf(cut(fromSeconds = 2.0, toSeconds = 10.0)))

    val candidates = detector.detect(file.toOkioPath(), cutResult = result, daiSlotsMs = listOf(4_000L))

    assertThat(candidates.map { it.timeMs }).isEqualTo(candidates.map { it.timeMs }.sorted())
  }

  @Test fun `a garbage-scale candidate list is capped`() = runBlocking {
    val file = tempFile(contentA)
    val result = AdCutter.Result.Skipped(
      "copies do not agree",
      cuts = (0 until 100).map { cut(fromSeconds = it * 20.0, toSeconds = it * 20.0 + 5.0) },
    )

    val candidates = detector.detect(file.toOkioPath(), cutResult = result, daiSlotsMs = emptyList())

    assertThat(candidates).hasSize(64)
    assertThat(logLines.filter { "truncating" in it }).hasSize(1)
  }

  @Test fun `a missing file never fails detection - file-independent signals still report`() = runBlocking {
    val result = AdCutter.Result.Skipped("too much", cuts = listOf(cut(fromSeconds = 6.5, toSeconds = 8.25)))

    val candidates = detector.detect("/nonexistent/episode.mp3".toPath(), cutResult = result, daiSlotsMs = listOf(1_000L))

    assertThat(candidates.map { it.source }).containsExactly(Source.DAI_SLOT, Source.DIFF_CUT, Source.DIFF_CUT)
  }

  @Test fun `a file with no mp3 frames or id3 tag yields nothing`() = runBlocking {
    val file = tempFile(ByteArray(4096) { (it % 251).toByte() })

    val candidates = detector.detect(file.toOkioPath(), cutResult = null, daiSlotsMs = emptyList())

    assertThat(candidates).isEmpty()
  }

  private fun cut(fromSeconds: Double, toSeconds: Double) =
    AdCutter.Cut(fromByte = 0, toByte = 0, fromSeconds = fromSeconds, toSeconds = toSeconds)

  private fun chapter(id: String, fromMs: Long, toMs: Long): ByteArray {
    val body = id.toByteArray(Charsets.ISO_8859_1) + 0 +
      int32(fromMs) + int32(toMs) + int32(0xFFFFFFFFL) + int32(0xFFFFFFFFL)
    return "CHAP".toByteArray(Charsets.ISO_8859_1) + int32(body.size.toLong()) + byteArrayOf(0, 0) + body
  }

  private fun buildId3(vararg frames: ByteArray): ByteArray {
    val body = frames.reduce(ByteArray::plus)
    val size = body.size
    val header = byteArrayOf(
      'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
      (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
      (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
    )
    return header + body
  }

  private fun int32(value: Long) = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
  )

  private fun tempFile(bytes: ByteArray): File =
    File.createTempFile("boundary-test", ".mp3").apply {
      deleteOnExit()
      writeBytes(bytes)
    }
}
