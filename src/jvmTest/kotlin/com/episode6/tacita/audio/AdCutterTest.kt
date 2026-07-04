package com.episode6.tacita.audio

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdCutterTest {

  private val parser = Mp3SegmentParser()
  private val adCutter = AdCutter()

  private val contentA = fixture("content-a.mp3") // ~6.1s
  private val contentB = fixture("content-b.mp3") // ~8.1s
  private val adA = fixture("ad-a.mp3") // ~2.1s
  private val adB = fixture("ad-b.mp3") // ~3.1s

  @Test fun `cuts ads that differ between the two copies`() = runBlocking {
    val file = tempFile(contentA + adA + contentB)
    val reference = tempFile(contentA + adB + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    assertSeconds(2.1, result.secondsRemoved)
    assertContentEquals(contentA + contentB, file.readBytes())
  }

  @Test fun `cuts ads absent from the reference copy`() = runBlocking {
    val file = tempFile(contentA + adA + contentB)
    val reference = tempFile(contentA + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    assertContentEquals(contentA + contentB, file.readBytes())
  }

  @Test fun `finds no ads when only the reference copy has them`() = runBlocking {
    val file = tempFile(contentA + contentB)
    val reference = tempFile(contentA + adB + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.NoAdsFound>(result)
    assertContentEquals(contentA + contentB, file.readBytes())
  }

  @Test fun `finds no ads when the copies are identical`() = runBlocking {
    val bytes = contentA + adA + contentB // even an ad is kept if both copies agree on it
    val file = tempFile(bytes)
    val reference = tempFile(bytes)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.NoAdsFound>(result)
    assertContentEquals(bytes, file.readBytes())
  }

  @Test fun `recovers content that lost its stitch marker`() = runBlocking {
    // strip content-b's leading tag frame so it merges into the ad's segment,
    // like an Acast stitch that drops the join marker
    val headerlessB = contentB.copyOfRange(parser.frames(contentB, 0, contentB.size).first().endByte, contentB.size)
    val file = tempFile(contentA + adA + headerlessB)
    val reference = tempFile(contentA + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    assertSeconds(2.1, result.secondsRemoved)
    assertContentEquals(contentA + headerlessB, file.readBytes())
  }

  @Test fun `keeps material served identically in both copies - the known blind spot`() = runBlocking {
    // a sticky ad with identical bytes in both copies is indistinguishable from baked-in
    // episode material; the cutter errs toward keeping it
    val bytes = contentA + adA + contentB
    val file = tempFile(bytes)
    val reference = tempFile(contentA + adA + adB + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.NoAdsFound>(result)
    assertContentEquals(bytes, file.readBytes())
  }

  @Test fun `cuts only the unshared part of a break that merged with stable material`() = runBlocking {
    // ad-b was injected right after stable material (ad-a here) without a stitch marker;
    // only the bytes absent from the reference are cut
    val headerlessAdB = adB.copyOfRange(parser.frames(adB, 0, adB.size).first().endByte, adB.size)
    val file = tempFile(contentA + adA + headerlessAdB + contentB)
    val reference = tempFile(contentA + adA + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    assertSeconds(3.1, result.secondsRemoved)
    assertContentEquals(contentA + adA + contentB, file.readBytes())
  }

  @Test fun `skips when too much of the file would be removed`() = runBlocking {
    val bytes = contentA + adA + adB + contentB // ~5.2s of ads in ~19.4s: over the 25% default
    val file = tempFile(bytes)
    val reference = tempFile(contentA + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.Skipped>(result)
    assertContentEquals(bytes, file.readBytes())
  }

  @Test fun `skips when the copies do not agree on enough of the file`() = runBlocking {
    val bytes = contentA + contentB
    val file = tempFile(bytes)
    val reference = tempFile(adA + adB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.Skipped>(result)
    assertContentEquals(bytes, file.readBytes())
  }

  @Test fun `cuts insertions with no stitch tag frames at all`() = runBlocking {
    // Audioboom-style: neither the inserted ad nor the following content carries a tag
    // frame, so segment structure is invisible and only byte alignment can find the ad
    val headerless = { bytes: ByteArray ->
      bytes.copyOfRange(parser.frames(bytes, 0, bytes.size).first().endByte, bytes.size)
    }
    val file = tempFile(contentA + headerless(adA) + headerless(contentB))
    val reference = tempFile(contentA + headerless(contentB))

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    assertSeconds(2.1, result.secondsRemoved)
    assertContentEquals(contentA + headerless(contentB), file.readBytes())
  }

  @Test fun `shifts id3 chapter timestamps by the material cut before them`() = runBlocking {
    // ad-a spans ~6.1s..8.2s of the composition; chapter 1 starts before it, chapter 2 after
    val id3 = buildId3(chapter(id = "ch0", fromMs = 0, toMs = 5_000), chapter(id = "ch1", fromMs = 10_000, toMs = 14_000))
    val file = tempFile(id3 + contentA + adA + contentB)
    val reference = tempFile(contentA + contentB)

    val result = adCutter.cutAds(file.toOkioPath(), reference.toOkioPath())

    assertIs<AdCutter.Result.AdsCut>(result)
    val shiftMs = (result.secondsRemoved * 1000).toLong()
    val chapters = parseChapters(file.readBytes())
    assertEquals(2, chapters.size)
    assertEquals(0L to 5_000L, chapters[0]) // entirely before the cut: untouched
    assertTrue(abs(chapters[1].first - (10_000 - shiftMs)) <= 2, "chapter 1 start: ${chapters[1]}")
    assertTrue(abs(chapters[1].second - (14_000 - shiftMs)) <= 2, "chapter 1 end: ${chapters[1]}")
    assertContentEquals(contentA + contentB, file.readBytes().copyOfRange(id3.size, id3.size + contentA.size + contentB.size))
  }

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

  private fun parseChapters(data: ByteArray): List<Pair<Long, Long>> {
    val chapters = mutableListOf<Pair<Long, Long>>()
    var pos = 10
    while (pos + 10 < data.size && String(data, pos, 4, Charsets.ISO_8859_1) == "CHAP") {
      val size = int32At(data, pos + 4).toInt()
      var p = pos + 10
      while (data[p] != 0.toByte()) p++
      p++
      chapters += int32At(data, p) to int32At(data, p + 4)
      pos += 10 + size
    }
    return chapters
  }

  private fun int32(value: Long) = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
  )

  private fun int32At(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xFF shl 24) or (data[at + 1].toLong() and 0xFF shl 16) or
      (data[at + 2].toLong() and 0xFF shl 8) or (data[at + 3].toLong() and 0xFF)

  private fun assertSeconds(expected: Double, actual: Double) =
    assertTrue(abs(expected - actual) < 0.5, "expected ~${expected}s, got ${actual}s")

  private fun tempFile(bytes: ByteArray): File =
    File.createTempFile("adcut-test", ".mp3").apply {
      deleteOnExit()
      writeBytes(bytes)
    }
}
