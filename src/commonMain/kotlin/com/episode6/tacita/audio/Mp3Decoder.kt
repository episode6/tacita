package com.episode6.tacita.audio

import kotlin.math.min

/**
 * A pure-Kotlin MPEG-1/2/2.5 Layer III decoder producing float PCM, ported from minimp3
 * (https://github.com/lieff/minimp3, CC0 — scalar path, float output, Layer III only; the
 * constant tables in [Mp3Tables] are extracted mechanically from the same source).
 *
 * This exists as groundwork for the acoustic ad-fingerprint layer (docs/ALGORITHM.md):
 * level-invariant fingerprinting runs over decoded PCM, and no mp3 decoder exists in common
 * code across the KMP targets. Function/variable names deliberately shadow minimp3's so the
 * port can be audited against the C side by side.
 *
 * Usage mirrors minimp3: repeatedly call [decodeFrame] at the current stream position and
 * advance by [FrameInfo.frameBytes]. A return of 0 with `frameBytes > 0` means the frame was
 * skipped (garbage, unsupported layer, or bit-reservoir priming); 0 with `frameBytes == 0`
 * means no more frames. Instances hold decode state (bit reservoir, MDCT/QMF overlap) and are
 * not thread-safe; use one instance per stream.
 */
internal class Mp3Decoder {

  /** Mirror of minimp3's `mp3dec_frame_info_t`, refilled by every [decodeFrame] call. */
  class FrameInfo {
    /** Bytes consumed from the input (skipped garbage + the frame itself). */
    var frameBytes: Int = 0

    /** Offset of the frame header inside the bytes just consumed. */
    var frameOffset: Int = 0
    var channels: Int = 0
    var hz: Int = 0
    var layer: Int = 0
    var bitrateKbps: Int = 0
  }

  val info: FrameInfo = FrameInfo()

  // mp3dec_t state
  private val mdctOverlap = Array(2) { FloatArray(9 * 32) }
  private val qmfState = FloatArray(15 * 2 * 32)
  private var reserv = 0
  private var freeFormatBytes = 0
  private val header = ByteArray(HDR_SIZE)
  private val reservBuf = ByteArray(MAX_BITRESERVOIR_BYTES)

  // mp3dec_scratch_t (member instead of per-call stack allocation)
  private val scratchBs = Bs()
  private val frameBs = Bs()
  private val maindata = ByteArray(MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES)
  private val grInfo = Array(4) { GrInfo() }
  private val grbuf = FloatArray(2 * 576) // both channels contiguous, like C's [2][576]
  private val scf = FloatArray(40)
  private val syn = FloatArray((18 + 15) * 2 * 32)
  private val istPos = Array(2) { IntArray(39) }

  // small per-frame scratch reused across calls
  private val iscf = IntArray(40)
  private val scfSize = IntArray(4)
  private val maxBand = IntArray(3)
  private val dct2Tmp = FloatArray(32)
  private val imdctCo = FloatArray(9)
  private val imdctSi = FloatArray(9)
  private val imdctTmp = FloatArray(18)
  private val synthA = FloatArray(4)
  private val synthB = FloatArray(4)
  private var foundFrameBytes = 0

  /** Forget all stream state (mirror of `mp3dec_init` + the resync memset). */
  fun reset() {
    fullReset()
  }

  /**
   * Decodes the first frame found in `mp3[offset ..< offset+length]` into [pcm] (interleaved,
   * `[-1, 1]` floats, up to [MAX_SAMPLES_PER_FRAME] values written at [pcmOffset]). Returns
   * samples produced per channel (0 = frame skipped or no frame; see class doc). Passing a
   * null [pcm] parses the frame header only.
   */
  fun decodeFrame(mp3: ByteArray, offset: Int, length: Int, pcm: FloatArray?, pcmOffset: Int = 0): Int {
    var i = 0
    var frameSize = 0
    if (length > 4 && header[0] == 0xFF.toByte() && hdrCompare(header, 0, mp3, offset)) {
      frameSize = hdrFrameBytes(mp3, offset, freeFormatBytes) + hdrPadding(mp3, offset)
      if (frameSize != length && (frameSize + HDR_SIZE > length || !hdrCompare(mp3, offset, mp3, offset + frameSize))) {
        frameSize = 0
      }
    }
    if (frameSize == 0) {
      fullReset()
      i = findFrame(mp3, offset, length)
      frameSize = foundFrameBytes
      if (frameSize == 0 || i + frameSize > length) {
        info.frameBytes = i
        return 0
      }
    }

    val hdr = offset + i
    mp3.copyInto(header, 0, hdr, hdr + HDR_SIZE)
    info.frameBytes = i + frameSize
    info.frameOffset = i
    info.channels = if (hdrIsMono(mp3, hdr)) 1 else 2
    info.hz = hdrSampleRateHz(mp3, hdr)
    info.layer = 4 - hdrGetLayer(mp3, hdr)
    info.bitrateKbps = hdrBitrateKbps(mp3, hdr)

    if (pcm == null) {
      return hdrFrameSamples(mp3, hdr)
    }

    frameBs.init(mp3, hdr + HDR_SIZE, frameSize - HDR_SIZE)
    if (hdrIsCrc(mp3, hdr)) {
      getBits(frameBs, 16)
    }

    if (info.layer != 3) {
      return 0 // MINIMP3_ONLY_MP3: report the frame via info so callers can skip it
    }
    val mainDataBegin = l3ReadSideInfo(frameBs, grInfo, mp3, hdr)
    if (mainDataBegin < 0 || frameBs.pos > frameBs.limit) {
      header[0] = 0
      return 0
    }
    val success = l3RestoreReservoir(frameBs, mainDataBegin)
    if (success) {
      var pcmPos = pcmOffset
      for (igr in 0 until (if (hdrTestMpeg1(mp3, hdr)) 2 else 1)) {
        grbuf.fill(0f)
        l3Decode(grInfo, igr * info.channels, info.channels)
        synthGranule(qmfState, grbuf, 18, info.channels, pcm, pcmPos, syn)
        pcmPos += 576 * info.channels
      }
    }
    l3SaveReservoir()
    return if (success) hdrFrameSamples(header, 0) else 0
  }

  private fun fullReset() {
    mdctOverlap[0].fill(0f)
    mdctOverlap[1].fill(0f)
    qmfState.fill(0f)
    reserv = 0
    freeFormatBytes = 0
    header.fill(0)
    reservBuf.fill(0)
  }

  // ---------------------------------------------------------------------------------------
  // Bitstream
  // ---------------------------------------------------------------------------------------

  private class Bs {
    var buf: ByteArray = ByteArray(0)
    var start = 0
    var pos = 0 // bits, relative to start
    var limit = 0 // bits

    fun init(buf: ByteArray, start: Int, bytes: Int) {
      this.buf = buf
      this.start = start
      pos = 0
      limit = bytes * 8
    }
  }

  private fun getBits(bs: Bs, n: Int): Int {
    val s = bs.pos and 7
    var shl = n + s
    var p = bs.start + (bs.pos ushr 3)
    bs.pos += n
    if (bs.pos > bs.limit) return 0
    var cache = 0
    var next = b(bs.buf, p++) and (255 shr s)
    shl -= 8
    while (shl > 0) {
      cache = cache or (next shl shl)
      next = b(bs.buf, p++)
      shl -= 8
    }
    return cache or (next ushr -shl)
  }

  // ---------------------------------------------------------------------------------------
  // Header parsing
  // ---------------------------------------------------------------------------------------

  private fun hdrIsMono(h: ByteArray, o: Int) = (b(h, o + 3) and 0xC0) == 0xC0
  private fun hdrIsMsStereo(h: ByteArray, o: Int) = (b(h, o + 3) and 0xE0) == 0x60
  private fun hdrIsFreeFormat(h: ByteArray, o: Int) = (b(h, o + 2) and 0xF0) == 0
  private fun hdrIsCrc(h: ByteArray, o: Int) = (b(h, o + 1) and 1) == 0
  private fun hdrTestPadding(h: ByteArray, o: Int) = (b(h, o + 2) and 0x2) != 0
  private fun hdrTestMpeg1(h: ByteArray, o: Int) = (b(h, o + 1) and 0x8) != 0
  private fun hdrTestNotMpeg25(h: ByteArray, o: Int) = (b(h, o + 1) and 0x10) != 0
  private fun hdrTestIStereo(h: ByteArray, o: Int) = (b(h, o + 3) and 0x10) != 0
  private fun hdrTestMsStereo(h: ByteArray, o: Int) = (b(h, o + 3) and 0x20) != 0
  private fun hdrGetLayer(h: ByteArray, o: Int) = (b(h, o + 1) shr 1) and 3
  private fun hdrGetBitrate(h: ByteArray, o: Int) = b(h, o + 2) shr 4
  private fun hdrGetSampleRate(h: ByteArray, o: Int) = (b(h, o + 2) shr 2) and 3
  private fun hdrGetMySampleRate(h: ByteArray, o: Int): Int {
    val h1 = b(h, o + 1)
    return hdrGetSampleRate(h, o) + (((h1 shr 3) and 1) + ((h1 shr 4) and 1)) * 3
  }

  private fun hdrIsFrame576(h: ByteArray, o: Int) = (b(h, o + 1) and 14) == 2
  private fun hdrIsLayer1(h: ByteArray, o: Int) = (b(h, o + 1) and 6) == 6

  private fun hdrValid(h: ByteArray, o: Int): Boolean =
    b(h, o) == 0xFF &&
      ((b(h, o + 1) and 0xF0) == 0xF0 || (b(h, o + 1) and 0xFE) == 0xE2) &&
      hdrGetLayer(h, o) != 0 &&
      hdrGetBitrate(h, o) != 15 &&
      hdrGetSampleRate(h, o) != 3

  private fun hdrCompare(h1: ByteArray, o1: Int, h2: ByteArray, o2: Int): Boolean =
    hdrValid(h2, o2) &&
      ((b(h1, o1 + 1) xor b(h2, o2 + 1)) and 0xFE) == 0 &&
      ((b(h1, o1 + 2) xor b(h2, o2 + 2)) and 0x0C) == 0 &&
      hdrIsFreeFormat(h1, o1) == hdrIsFreeFormat(h2, o2)

  private fun hdrBitrateKbps(h: ByteArray, o: Int): Int =
    2 * Mp3Tables.HALFRATE[if (hdrTestMpeg1(h, o)) 1 else 0][hdrGetLayer(h, o) - 1][hdrGetBitrate(h, o)]

  private fun hdrSampleRateHz(h: ByteArray, o: Int): Int {
    val hz = when (hdrGetSampleRate(h, o)) {
      0 -> 44100
      1 -> 48000
      else -> 32000
    }
    return hz shr (if (hdrTestMpeg1(h, o)) 0 else 1) shr (if (hdrTestNotMpeg25(h, o)) 0 else 1)
  }

  private fun hdrFrameSamples(h: ByteArray, o: Int): Int =
    if (hdrIsLayer1(h, o)) 384 else (1152 shr (if (hdrIsFrame576(h, o)) 1 else 0))

  private fun hdrFrameBytes(h: ByteArray, o: Int, freeFormatSize: Int): Int {
    var frameBytes = hdrFrameSamples(h, o) * hdrBitrateKbps(h, o) * 125 / hdrSampleRateHz(h, o)
    if (hdrIsLayer1(h, o)) {
      frameBytes = frameBytes and 3.inv() // slot align
    }
    return if (frameBytes != 0) frameBytes else freeFormatSize
  }

  private fun hdrPadding(h: ByteArray, o: Int): Int =
    if (hdrTestPadding(h, o)) (if (hdrIsLayer1(h, o)) 4 else 1) else 0

  // ---------------------------------------------------------------------------------------
  // Frame sync
  // ---------------------------------------------------------------------------------------

  private fun matchFrame(mp3: ByteArray, hdr: Int, mp3Bytes: Int, frameBytes: Int): Boolean {
    var i = 0
    var nmatch = 0
    while (nmatch < MAX_FRAME_SYNC_MATCHES) {
      i += hdrFrameBytes(mp3, hdr + i, frameBytes) + hdrPadding(mp3, hdr + i)
      if (i + HDR_SIZE > mp3Bytes) return nmatch > 0
      if (!hdrCompare(mp3, hdr, mp3, hdr + i)) return false
      nmatch++
    }
    return true
  }

  /** `mp3d_find_frame`: returns skipped-byte count; sets [foundFrameBytes] & [freeFormatBytes]. */
  private fun findFrame(mp3: ByteArray, offset: Int, mp3Bytes: Int): Int {
    for (i in 0 until mp3Bytes - HDR_SIZE) {
      if (hdrValid(mp3, offset + i)) {
        var frameBytes = hdrFrameBytes(mp3, offset + i, freeFormatBytes)
        var frameAndPadding = frameBytes + hdrPadding(mp3, offset + i)

        var k = HDR_SIZE
        while (frameBytes == 0 && k < MAX_FREE_FORMAT_FRAME_SIZE && i + 2 * k < mp3Bytes - HDR_SIZE) {
          if (hdrCompare(mp3, offset + i, mp3, offset + i + k)) {
            val fb = k - hdrPadding(mp3, offset + i)
            val nextfb = fb + hdrPadding(mp3, offset + i + k)
            if (i + k + nextfb + HDR_SIZE <= mp3Bytes && hdrCompare(mp3, offset + i, mp3, offset + i + k + nextfb)) {
              frameAndPadding = k
              frameBytes = fb
              freeFormatBytes = fb
            }
          }
          k++
        }
        if ((frameBytes != 0 && i + frameAndPadding <= mp3Bytes &&
            matchFrame(mp3, offset + i, mp3Bytes - i, frameBytes)) ||
          (i == 0 && frameAndPadding == mp3Bytes)
        ) {
          foundFrameBytes = frameAndPadding
          return i
        }
        freeFormatBytes = 0
      }
    }
    foundFrameBytes = 0
    return mp3Bytes
  }

  // ---------------------------------------------------------------------------------------
  // Layer III side info / scalefactors
  // ---------------------------------------------------------------------------------------

  private class GrInfo {
    var sfbTab: IntArray = Mp3Tables.SCF_LONG[0]
    var part23Length = 0
    var bigValues = 0
    var scalefacCompress = 0
    var globalGain = 0
    var blockType = 0
    var mixedBlockFlag = 0
    var nLongSfb = 0
    var nShortSfb = 0
    val tableSelect = IntArray(3)
    val regionCount = IntArray(3)
    val subblockGain = IntArray(3)
    var preflag = 0
    var scalefacScale = 0
    var count1Table = 0
    var scfsi = 0
  }

  private fun l3ReadSideInfo(bs: Bs, gr: Array<GrInfo>, hdr: ByteArray, ho: Int): Int {
    var tables: Int
    var scfsi = 0
    val mainDataBegin: Int
    var part23Sum = 0
    var srIdx = hdrGetMySampleRate(hdr, ho)
    if (srIdx != 0) srIdx--
    var grCount = if (hdrIsMono(hdr, ho)) 1 else 2

    if (hdrTestMpeg1(hdr, ho)) {
      grCount *= 2
      mainDataBegin = getBits(bs, 9)
      scfsi = getBits(bs, 7 + grCount)
    } else {
      mainDataBegin = getBits(bs, 8 + grCount) shr grCount
    }

    var g = 0
    do {
      val cur = gr[g]
      if (hdrIsMono(hdr, ho)) {
        scfsi = scfsi shl 4
      }
      cur.part23Length = getBits(bs, 12)
      part23Sum += cur.part23Length
      cur.bigValues = getBits(bs, 9)
      if (cur.bigValues > 288) {
        return -1
      }
      cur.globalGain = getBits(bs, 8)
      cur.scalefacCompress = getBits(bs, if (hdrTestMpeg1(hdr, ho)) 4 else 9)
      cur.sfbTab = Mp3Tables.SCF_LONG[srIdx]
      cur.nLongSfb = 22
      cur.nShortSfb = 0
      if (getBits(bs, 1) != 0) {
        cur.blockType = getBits(bs, 2)
        if (cur.blockType == 0) {
          return -1
        }
        cur.mixedBlockFlag = getBits(bs, 1)
        cur.regionCount[0] = 7
        cur.regionCount[1] = 255
        if (cur.blockType == SHORT_BLOCK_TYPE) {
          scfsi = scfsi and 0x0F0F
          if (cur.mixedBlockFlag == 0) {
            cur.regionCount[0] = 8
            cur.sfbTab = Mp3Tables.SCF_SHORT[srIdx]
            cur.nLongSfb = 0
            cur.nShortSfb = 39
          } else {
            cur.sfbTab = Mp3Tables.SCF_MIXED[srIdx]
            cur.nLongSfb = if (hdrTestMpeg1(hdr, ho)) 8 else 6
            cur.nShortSfb = 30
          }
        }
        tables = getBits(bs, 10)
        tables = tables shl 5
        cur.subblockGain[0] = getBits(bs, 3)
        cur.subblockGain[1] = getBits(bs, 3)
        cur.subblockGain[2] = getBits(bs, 3)
      } else {
        cur.blockType = 0
        cur.mixedBlockFlag = 0
        tables = getBits(bs, 15)
        cur.regionCount[0] = getBits(bs, 4)
        cur.regionCount[1] = getBits(bs, 3)
        cur.regionCount[2] = 255
      }
      cur.tableSelect[0] = tables shr 10
      cur.tableSelect[1] = (tables shr 5) and 31
      cur.tableSelect[2] = tables and 31
      cur.preflag = if (hdrTestMpeg1(hdr, ho)) getBits(bs, 1) else (if (cur.scalefacCompress >= 500) 1 else 0)
      cur.scalefacScale = getBits(bs, 1)
      cur.count1Table = getBits(bs, 1)
      cur.scfsi = (scfsi shr 12) and 15
      scfsi = scfsi shl 4
      g++
    } while (--grCount > 0)

    if (part23Sum + bs.pos > bs.limit + mainDataBegin * 8) {
      return -1
    }
    return mainDataBegin
  }

  private fun l3ReadScalefactors(
    scf: IntArray,
    istPos: IntArray,
    scfSize: IntArray,
    scfCount: IntArray,
    scfCountOff: Int,
    bitbuf: Bs,
    scfsiIn: Int,
  ) {
    var scfsi = scfsiIn
    var scfOff = 0
    var istOff = 0
    var i = 0
    while (i < 4 && scfCount[scfCountOff + i] != 0) {
      val cnt = scfCount[scfCountOff + i]
      if ((scfsi and 8) != 0) {
        istPos.copyInto(scf, scfOff, istOff, istOff + cnt)
      } else {
        val bits = scfSize[i]
        if (bits == 0) {
          scf.fill(0, scfOff, scfOff + cnt)
          istPos.fill(0, istOff, istOff + cnt)
        } else {
          val maxScf = if (scfsi < 0) (1 shl bits) - 1 else -1
          for (k in 0 until cnt) {
            val s = getBits(bitbuf, bits)
            istPos[istOff + k] = if (s == maxScf) 255 else s
            scf[scfOff + k] = s
          }
        }
      }
      istOff += cnt
      scfOff += cnt
      i++
      scfsi *= 2
    }
    scf[scfOff] = 0
    scf[scfOff + 1] = 0
    scf[scfOff + 2] = 0
  }

  private fun l3LdexpQ2(yIn: Float, expQ2In: Int): Float {
    var y = yIn
    var expQ2 = expQ2In
    do {
      val e = min(30 * 4, expQ2)
      y *= Mp3Tables.EXPFRAC[e and 3] * (1 shl 30 shr (e shr 2)).toFloat()
      expQ2 -= e
    } while (expQ2 > 0)
    return y
  }

  private fun l3DecodeScalefactors(hdr: ByteArray, ho: Int, istPos: IntArray, bs: Bs, gr: GrInfo, scf: FloatArray, ch: Int) {
    val partitionIdx = (if (gr.nShortSfb != 0) 1 else 0) + (if (gr.nLongSfb == 0) 1 else 0)
    val scfPartition = Mp3Tables.SCF_PARTITIONS[partitionIdx]
    var scfPartitionOff = 0
    val scfShift = gr.scalefacScale + 1
    var scfsi = gr.scfsi

    if (hdrTestMpeg1(hdr, ho)) {
      val part = Mp3Tables.SCFC_DECODE[gr.scalefacCompress]
      scfSize[1] = part shr 2
      scfSize[0] = part shr 2
      scfSize[3] = part and 3
      scfSize[2] = part and 3
    } else {
      val mod = Mp3Tables.MOD
      val ist = if (hdrTestIStereo(hdr, ho) && ch != 0) 1 else 0
      var sfc = gr.scalefacCompress shr ist
      var k = ist * 3 * 4
      while (sfc >= 0) {
        var modprod = 1
        for (i in 3 downTo 0) {
          scfSize[i] = sfc / modprod % mod[k + i]
          modprod *= mod[k + i]
        }
        sfc -= modprod
        k += 4
      }
      scfPartitionOff = k
      scfsi = -16
    }
    l3ReadScalefactors(iscf, istPos, scfSize, scfPartition, scfPartitionOff, bs, scfsi)

    if (gr.nShortSfb != 0) {
      val sh = 3 - scfShift
      var i = 0
      while (i < gr.nShortSfb) {
        iscf[gr.nLongSfb + i + 0] += gr.subblockGain[0] shl sh
        iscf[gr.nLongSfb + i + 1] += gr.subblockGain[1] shl sh
        iscf[gr.nLongSfb + i + 2] += gr.subblockGain[2] shl sh
        i += 3
      }
    } else if (gr.preflag != 0) {
      for (i in 0 until 10) {
        iscf[11 + i] += Mp3Tables.PREAMP[i]
      }
    }

    val gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (if (hdrIsMsStereo(hdr, ho)) 2 else 0)
    val gain = l3LdexpQ2((1 shl (MAX_SCFI / 4)).toFloat(), MAX_SCFI - gainExp)
    for (i in 0 until gr.nLongSfb + gr.nShortSfb) {
      scf[i] = l3LdexpQ2(gain, iscf[i] shl scfShift)
    }
  }

  private fun l3Pow43(x: Int): Float {
    if (x < 129) {
      return Mp3Tables.POW43[16 + x]
    }
    var xx = x
    var mult = 256
    if (xx < 1024) {
      mult = 16
      xx = xx shl 3
    }
    val sign = (2 * xx) and 64
    val frac = ((xx and 63) - sign).toFloat() / ((xx and 63.inv()) + sign)
    return Mp3Tables.POW43[16 + ((xx + sign) shr 6)] * (1f + frac * ((4f / 3) + frac * (2f / 9))) * mult
  }

  // ---------------------------------------------------------------------------------------
  // Layer III huffman decode
  // ---------------------------------------------------------------------------------------

  @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
  private fun l3Huffman(dst: FloatArray, dstOffIn: Int, bs: Bs, gr: GrInfo, scfF: FloatArray, layer3grLimit: Int) {
    val tabs = Mp3Tables.HUFF_TABS
    val tabIndex = Mp3Tables.HUFF_TABINDEX
    val gLinbits = Mp3Tables.HUFF_LINBITS
    val pow43 = Mp3Tables.POW43

    var one = 0f
    var ireg = 0
    var bigValCnt = gr.bigValues
    val sfb = gr.sfbTab
    var sfbI = 0
    var scfI = 0
    val buf = bs.buf
    var nextPos = bs.start + bs.pos / 8
    var cache = ((byteAt(buf, nextPos) shl 24) or (byteAt(buf, nextPos + 1) shl 16) or
      (byteAt(buf, nextPos + 2) shl 8) or byteAt(buf, nextPos + 3)) shl (bs.pos and 7)
    var sh = (bs.pos and 7) - 8
    nextPos += 4
    var dstOff = dstOffIn
    var np: Int

    while (bigValCnt > 0) {
      val tabNum = gr.tableSelect[ireg]
      var sfbCnt = gr.regionCount[ireg++]
      val codebook = tabIndex[tabNum]
      val linbits = gLinbits[tabNum]
      if (linbits != 0) {
        while (true) {
          np = sfb[sfbI++] / 2
          var pairsToDecode = min(bigValCnt, np)
          one = scfF[scfI++]
          do {
            var w = 5
            var leaf = tabs[codebook + (cache ushr (32 - w))]
            while (leaf < 0) {
              cache = cache shl w
              sh += w
              w = leaf and 7
              leaf = tabs[codebook + (cache ushr (32 - w)) - (leaf shr 3)]
            }
            cache = cache shl (leaf shr 8)
            sh += leaf shr 8

            for (j in 0 until 2) {
              var lsb = leaf and 0x0F
              if (lsb == 15) {
                lsb += cache ushr (32 - linbits)
                cache = cache shl linbits
                sh += linbits
                while (sh >= 0) {
                  cache = cache or (byteAt(buf, nextPos++) shl sh)
                  sh -= 8
                }
                dst[dstOff] = one * l3Pow43(lsb) * (if (cache < 0) -1f else 1f)
              } else {
                dst[dstOff] = pow43[16 + lsb - 16 * (cache ushr 31)] * one
              }
              if (lsb != 0) {
                cache = cache shl 1
                sh += 1
              }
              dstOff++
              leaf = leaf shr 4
            }
            while (sh >= 0) {
              cache = cache or (byteAt(buf, nextPos++) shl sh)
              sh -= 8
            }
          } while (--pairsToDecode > 0)
          bigValCnt -= np
          if (bigValCnt <= 0 || --sfbCnt < 0) break
        }
      } else {
        while (true) {
          np = sfb[sfbI++] / 2
          var pairsToDecode = min(bigValCnt, np)
          one = scfF[scfI++]
          do {
            var w = 5
            var leaf = tabs[codebook + (cache ushr (32 - w))]
            while (leaf < 0) {
              cache = cache shl w
              sh += w
              w = leaf and 7
              leaf = tabs[codebook + (cache ushr (32 - w)) - (leaf shr 3)]
            }
            cache = cache shl (leaf shr 8)
            sh += leaf shr 8

            for (j in 0 until 2) {
              val lsb = leaf and 0x0F
              dst[dstOff] = pow43[16 + lsb - 16 * (cache ushr 31)] * one
              if (lsb != 0) {
                cache = cache shl 1
                sh += 1
              }
              dstOff++
              leaf = leaf shr 4
            }
            while (sh >= 0) {
              cache = cache or (byteAt(buf, nextPos++) shl sh)
              sh -= 8
            }
          } while (--pairsToDecode > 0)
          bigValCnt -= np
          if (bigValCnt <= 0 || --sfbCnt < 0) break
        }
      }
    }

    np = 1 - bigValCnt
    count1@ while (true) {
      val codebookCount1 = if (gr.count1Table != 0) Mp3Tables.HUFF_TAB33 else Mp3Tables.HUFF_TAB32
      var leaf = codebookCount1[cache ushr 28]
      if ((leaf and 8) == 0) {
        leaf = codebookCount1[(leaf shr 3) + ((cache shl 4) ushr (32 - (leaf and 3)))]
      }
      cache = cache shl (leaf and 7)
      sh += leaf and 7
      if ((nextPos - bs.start) * 8 - 24 + sh > layer3grLimit) {
        break
      }
      if (--np == 0) {
        np = sfb[sfbI++] / 2
        if (np == 0) break
        one = scfF[scfI++]
      }
      if ((leaf and (128 shr 0)) != 0) {
        dst[dstOff + 0] = if (cache < 0) -one else one
        cache = cache shl 1
        sh += 1
      }
      if ((leaf and (128 shr 1)) != 0) {
        dst[dstOff + 1] = if (cache < 0) -one else one
        cache = cache shl 1
        sh += 1
      }
      if (--np == 0) {
        np = sfb[sfbI++] / 2
        if (np == 0) break
        one = scfF[scfI++]
      }
      if ((leaf and (128 shr 2)) != 0) {
        dst[dstOff + 2] = if (cache < 0) -one else one
        cache = cache shl 1
        sh += 1
      }
      if ((leaf and (128 shr 3)) != 0) {
        dst[dstOff + 3] = if (cache < 0) -one else one
        cache = cache shl 1
        sh += 1
      }
      while (sh >= 0) {
        cache = cache or (byteAt(buf, nextPos++) shl sh)
        sh -= 8
      }
      dstOff += 4
    }

    bs.pos = layer3grLimit
  }

  // ---------------------------------------------------------------------------------------
  // Layer III stereo processing
  // ---------------------------------------------------------------------------------------

  private fun l3MidsideStereo(gr: FloatArray, left: Int, n: Int) {
    val right = left + 576
    for (i in 0 until n) {
      val a = gr[left + i]
      val bb = gr[right + i]
      gr[left + i] = a + bb
      gr[right + i] = a - bb
    }
  }

  private fun l3IntensityStereoBand(gr: FloatArray, left: Int, n: Int, kl: Float, kr: Float) {
    for (i in 0 until n) {
      gr[left + i + 576] = gr[left + i] * kr
      gr[left + i] = gr[left + i] * kl
    }
  }

  private fun l3StereoTopBand(gr: FloatArray, rightIn: Int, sfb: IntArray, nbands: Int, maxBand: IntArray) {
    var right = rightIn
    maxBand[0] = -1
    maxBand[1] = -1
    maxBand[2] = -1

    for (i in 0 until nbands) {
      var k = 0
      while (k < sfb[i]) {
        if (gr[right + k] != 0f || gr[right + k + 1] != 0f) {
          maxBand[i % 3] = i
          break
        }
        k += 2
      }
      right += sfb[i]
    }
  }

  private fun l3StereoProcess(
    gr: FloatArray,
    leftIn: Int,
    istPos: IntArray,
    sfb: IntArray,
    hdr: ByteArray,
    ho: Int,
    maxBand: IntArray,
    mpeg2Sh: Int,
  ) {
    var left = leftIn
    val maxPos = if (hdrTestMpeg1(hdr, ho)) 7 else 64
    var i = 0
    while (sfb[i] != 0) {
      val ipos = istPos[i]
      if (i > maxBand[i % 3] && ipos < maxPos) {
        var kl: Float
        var kr: Float
        val s = if (hdrTestMsStereo(hdr, ho)) 1.41421356f else 1f
        if (hdrTestMpeg1(hdr, ho)) {
          kl = Mp3Tables.PAN[2 * ipos]
          kr = Mp3Tables.PAN[2 * ipos + 1]
        } else {
          kl = 1f
          kr = l3LdexpQ2(1f, ((ipos + 1) shr 1) shl mpeg2Sh)
          if ((ipos and 1) != 0) {
            kl = kr
            kr = 1f
          }
        }
        l3IntensityStereoBand(gr, left, sfb[i], kl * s, kr * s)
      } else if (hdrTestMsStereo(hdr, ho)) {
        l3MidsideStereo(gr, left, sfb[i])
      }
      left += sfb[i]
      i++
    }
  }

  private fun l3IntensityStereo(gr: FloatArray, istPos: IntArray, grInfo: Array<GrInfo>, grBase: Int, hdr: ByteArray, ho: Int) {
    val g = grInfo[grBase]
    val nSfb = g.nLongSfb + g.nShortSfb
    val maxBlocks = if (g.nShortSfb != 0) 3 else 1

    l3StereoTopBand(gr, 576, g.sfbTab, nSfb, maxBand)
    if (g.nLongSfb != 0) {
      val m = maxOf(maxBand[0], maxBand[1], maxBand[2])
      maxBand[0] = m
      maxBand[1] = m
      maxBand[2] = m
    }
    for (i in 0 until maxBlocks) {
      val defaultPos = if (hdrTestMpeg1(hdr, ho)) 3 else 0
      val itop = nSfb - maxBlocks + i
      val prev = itop - maxBlocks
      istPos[itop] = if (maxBand[i] >= prev) defaultPos else istPos[prev]
    }
    l3StereoProcess(gr, 0, istPos, g.sfbTab, hdr, ho, maxBand, grInfo[grBase + 1].scalefacCompress and 1)
  }

  // ---------------------------------------------------------------------------------------
  // Layer III reorder / antialias / IMDCT
  // ---------------------------------------------------------------------------------------

  private fun l3Reorder(gr: FloatArray, off: Int, scratch: FloatArray, sfb: IntArray, sfbOffIn: Int) {
    var src = off
    var dst = 0
    var sfbOff = sfbOffIn
    while (sfb[sfbOff] != 0) {
      val len = sfb[sfbOff]
      for (i in 0 until len) {
        scratch[dst++] = gr[src + 0 * len]
        scratch[dst++] = gr[src + 1 * len]
        scratch[dst++] = gr[src + 2 * len]
        src++
      }
      sfbOff += 3
      src += 2 * len
    }
    scratch.copyInto(gr, off, 0, dst)
  }

  private fun l3Antialias(gr: FloatArray, offIn: Int, nbandsIn: Int) {
    val aa = Mp3Tables.ANTIALIAS
    var off = offIn
    var nbands = nbandsIn
    while (nbands > 0) {
      for (i in 0 until 8) {
        val u = gr[off + 18 + i]
        val d = gr[off + 17 - i]
        gr[off + 18 + i] = u * aa[0][i] - d * aa[1][i]
        gr[off + 17 - i] = u * aa[1][i] + d * aa[0][i]
      }
      nbands--
      off += 18
    }
  }

  private fun l3Dct39(y: FloatArray) {
    var s0 = y[0]
    var s2 = y[2]
    var s4 = y[4]
    var s6 = y[6]
    var s8 = y[8]
    val t0 = s0 + s6 * 0.5f
    s0 -= s6
    val t4 = (s4 + s2) * 0.93969262f
    val t2 = (s8 + s2) * 0.76604444f
    s6 = (s4 - s8) * 0.17364818f
    s4 += s8 - s2

    s2 = s0 - s4 * 0.5f
    y[4] = s4 + s0
    s8 = t0 - t2 + s6
    s0 = t0 - t4 + t2
    s4 = t0 + t4 - s6

    var s1 = y[1]
    var s3 = y[3]
    var s5 = y[5]
    var s7 = y[7]

    s3 *= 0.86602540f
    val u0 = (s5 + s1) * 0.98480775f
    val u4 = (s5 - s7) * 0.34202014f
    val u2 = (s1 + s7) * 0.64278761f
    s1 = (s1 - s5 - s7) * 0.86602540f

    s5 = u0 - s3 - u2
    s7 = u4 - s3 - u0
    s3 = u4 + s3 - u2

    y[0] = s4 - s7
    y[1] = s2 + s1
    y[2] = s0 - s3
    y[3] = s8 + s5
    y[5] = s8 - s5
    y[6] = s0 + s3
    y[7] = s2 - s1
    y[8] = s4 + s7
  }

  private fun l3Imdct36(gr: FloatArray, grOffIn: Int, overlap: FloatArray, ovOffIn: Int, window: FloatArray, nbands: Int) {
    val twid9 = Mp3Tables.TWID9
    val co = imdctCo
    val si = imdctSi
    var grOff = grOffIn
    var ovOff = ovOffIn
    for (j in 0 until nbands) {
      co[0] = -gr[grOff + 0]
      si[0] = gr[grOff + 17]
      for (i in 0 until 4) {
        si[8 - 2 * i] = gr[grOff + 4 * i + 1] - gr[grOff + 4 * i + 2]
        co[1 + 2 * i] = gr[grOff + 4 * i + 1] + gr[grOff + 4 * i + 2]
        si[7 - 2 * i] = gr[grOff + 4 * i + 4] - gr[grOff + 4 * i + 3]
        co[2 + 2 * i] = -(gr[grOff + 4 * i + 3] + gr[grOff + 4 * i + 4])
      }
      l3Dct39(co)
      l3Dct39(si)

      si[1] = -si[1]
      si[3] = -si[3]
      si[5] = -si[5]
      si[7] = -si[7]

      for (i in 0 until 9) {
        val ovl = overlap[ovOff + i]
        val sum = co[i] * twid9[9 + i] + si[i] * twid9[0 + i]
        overlap[ovOff + i] = co[i] * twid9[0 + i] - si[i] * twid9[9 + i]
        gr[grOff + i] = ovl * window[0 + i] - sum * window[9 + i]
        gr[grOff + 17 - i] = ovl * window[9 + i] + sum * window[0 + i]
      }
      grOff += 18
      ovOff += 9
    }
  }

  private fun l3Idct3(x0: Float, x1: Float, x2: Float, dst: FloatArray) {
    val m1 = x1 * 0.86602540f
    val a1 = x0 - x2 * 0.5f
    dst[1] = x0 + x2
    dst[0] = a1 + m1
    dst[2] = a1 - m1
  }

  private val idct3Co = FloatArray(3)
  private val idct3Si = FloatArray(3)

  private fun l3Imdct12(x: FloatArray, xOff: Int, dst: FloatArray, dstOff: Int, overlap: FloatArray, ovOff: Int) {
    val twid3 = Mp3Tables.TWID3
    val co = idct3Co
    val si = idct3Si

    l3Idct3(-x[xOff + 0], x[xOff + 6] + x[xOff + 3], x[xOff + 12] + x[xOff + 9], co)
    l3Idct3(x[xOff + 15], x[xOff + 12] - x[xOff + 9], x[xOff + 6] - x[xOff + 3], si)
    si[1] = -si[1]

    for (i in 0 until 3) {
      val ovl = overlap[ovOff + i]
      val sum = co[i] * twid3[3 + i] + si[i] * twid3[0 + i]
      overlap[ovOff + i] = co[i] * twid3[0 + i] - si[i] * twid3[3 + i]
      dst[dstOff + i] = ovl * twid3[2 - i] - sum * twid3[5 - i]
      dst[dstOff + 5 - i] = ovl * twid3[5 - i] + sum * twid3[2 - i]
    }
  }

  private fun l3ImdctShort(gr: FloatArray, grOffIn: Int, overlap: FloatArray, ovOffIn: Int, nbandsIn: Int) {
    var grOff = grOffIn
    var ovOff = ovOffIn
    var nbands = nbandsIn
    val tmp = imdctTmp
    while (nbands > 0) {
      gr.copyInto(tmp, 0, grOff, grOff + 18)
      overlap.copyInto(gr, grOff, ovOff, ovOff + 6)
      l3Imdct12(tmp, 0, gr, grOff + 6, overlap, ovOff + 6)
      l3Imdct12(tmp, 1, gr, grOff + 12, overlap, ovOff + 6)
      l3Imdct12(tmp, 2, overlap, ovOff, overlap, ovOff + 6)
      nbands--
      ovOff += 9
      grOff += 18
    }
  }

  private fun l3ChangeSign(gr: FloatArray, offIn: Int) {
    var off = offIn + 18
    var bnd = 0
    while (bnd < 32) {
      var i = 1
      while (i < 18) {
        gr[off + i] = -gr[off + i]
        i += 2
      }
      bnd += 2
      off += 36
    }
  }

  private fun l3ImdctGr(gr: FloatArray, offIn: Int, overlap: FloatArray, blockType: Int, nLongBands: Int) {
    var off = offIn
    var ovOff = 0
    if (nLongBands != 0) {
      l3Imdct36(gr, off, overlap, ovOff, Mp3Tables.MDCT_WINDOW[0], nLongBands)
      off += 18 * nLongBands
      ovOff += 9 * nLongBands
    }
    if (blockType == SHORT_BLOCK_TYPE) {
      l3ImdctShort(gr, off, overlap, ovOff, 32 - nLongBands)
    } else {
      l3Imdct36(gr, off, overlap, ovOff, Mp3Tables.MDCT_WINDOW[if (blockType == STOP_BLOCK_TYPE) 1 else 0], 32 - nLongBands)
    }
  }

  // ---------------------------------------------------------------------------------------
  // Bit reservoir
  // ---------------------------------------------------------------------------------------

  private fun l3SaveReservoir() {
    var pos = (scratchBs.pos + 7) / 8
    var remains = scratchBs.limit / 8 - pos
    if (remains > MAX_BITRESERVOIR_BYTES) {
      pos += remains - MAX_BITRESERVOIR_BYTES
      remains = MAX_BITRESERVOIR_BYTES
    }
    if (remains > 0) {
      maindata.copyInto(reservBuf, 0, pos, pos + remains)
    }
    reserv = remains
  }

  private fun l3RestoreReservoir(bs: Bs, mainDataBegin: Int): Boolean {
    val frameBytes = (bs.limit - bs.pos) / 8
    val bytesHave = min(reserv, mainDataBegin)
    reservBuf.copyInto(maindata, 0, maxOf(0, reserv - mainDataBegin), maxOf(0, reserv - mainDataBegin) + bytesHave)
    val frameStart = bs.start + bs.pos / 8
    bs.buf.copyInto(maindata, bytesHave, frameStart, frameStart + frameBytes)
    scratchBs.init(maindata, 0, bytesHave + frameBytes)
    return reserv >= mainDataBegin
  }

  // ---------------------------------------------------------------------------------------
  // Layer III granule decode
  // ---------------------------------------------------------------------------------------

  private fun l3Decode(grInfo: Array<GrInfo>, grBase: Int, nch: Int) {
    for (ch in 0 until nch) {
      val layer3grLimit = scratchBs.pos + grInfo[grBase + ch].part23Length
      l3DecodeScalefactors(header, 0, istPos[ch], scratchBs, grInfo[grBase + ch], scf, ch)
      l3Huffman(grbuf, 576 * ch, scratchBs, grInfo[grBase + ch], scf, layer3grLimit)
    }

    if (hdrTestIStereo(header, 0)) {
      l3IntensityStereo(grbuf, istPos[1], grInfo, grBase, header, 0)
    } else if (hdrIsMsStereo(header, 0)) {
      l3MidsideStereo(grbuf, 0, 576)
    }

    for (ch in 0 until nch) {
      val g = grInfo[grBase + ch]
      var aaBands = 31
      val nLongBands = (if (g.mixedBlockFlag != 0) 2 else 0) shl (if (hdrGetMySampleRate(header, 0) == 2) 1 else 0)

      if (g.nShortSfb != 0) {
        aaBands = nLongBands - 1
        l3Reorder(grbuf, 576 * ch + nLongBands * 18, syn, g.sfbTab, g.nLongSfb)
      }

      l3Antialias(grbuf, 576 * ch, aaBands)
      l3ImdctGr(grbuf, 576 * ch, mdctOverlap[ch], g.blockType, nLongBands)
      l3ChangeSign(grbuf, 576 * ch)
    }
  }

  // ---------------------------------------------------------------------------------------
  // Synthesis filterbank
  // ---------------------------------------------------------------------------------------

  private fun dctII(gr: FloatArray, offIn: Int, n: Int) {
    val sec = Mp3Tables.DCT_SEC
    val t = dct2Tmp
    for (k in 0 until n) {
      var y = offIn + k
      for (i in 0 until 8) {
        val x0 = gr[y + i * 18]
        val x1 = gr[y + (15 - i) * 18]
        val x2 = gr[y + (16 + i) * 18]
        val x3 = gr[y + (31 - i) * 18]
        val t0 = x0 + x3
        val t1 = x1 + x2
        val t2 = (x1 - x2) * sec[3 * i + 0]
        val t3 = (x0 - x3) * sec[3 * i + 1]
        t[i] = t0 + t1
        t[8 + i] = (t0 - t1) * sec[3 * i + 2]
        t[16 + i] = t3 + t2
        t[24 + i] = (t3 - t2) * sec[3 * i + 2]
      }
      for (i in 0 until 4) {
        val base = 8 * i
        var x0 = t[base + 0]
        var x1 = t[base + 1]
        var x2 = t[base + 2]
        var x3 = t[base + 3]
        var x4 = t[base + 4]
        var x5 = t[base + 5]
        var x6 = t[base + 6]
        var x7 = t[base + 7]
        var xt: Float
        xt = x0 - x7; x0 += x7
        x7 = x1 - x6; x1 += x6
        x6 = x2 - x5; x2 += x5
        x5 = x3 - x4; x3 += x4
        x4 = x0 - x3; x0 += x3
        x3 = x1 - x2; x1 += x2
        t[base + 0] = x0 + x1
        t[base + 4] = (x0 - x1) * 0.70710677f
        x5 += x6
        x6 = (x6 + x7) * 0.70710677f
        x7 += xt
        x3 = (x3 + x4) * 0.70710677f
        x5 -= x7 * 0.198912367f // rotate by PI/8
        x7 += x5 * 0.382683432f
        x5 -= x7 * 0.198912367f
        val y0 = xt - x6
        xt += x6
        t[base + 1] = (xt + x7) * 0.50979561f
        t[base + 2] = (x4 + x3) * 0.54119611f
        t[base + 3] = (y0 - x5) * 0.60134488f
        t[base + 5] = (y0 + x5) * 0.89997619f
        t[base + 6] = (x4 - x3) * 1.30656302f
        t[base + 7] = (xt - x7) * 2.56291556f
      }
      for (i in 0 until 7) {
        gr[y + 0 * 18] = t[i]
        gr[y + 1 * 18] = t[16 + i] + t[24 + i] + t[24 + i + 1]
        gr[y + 2 * 18] = t[8 + i] + t[8 + i + 1]
        gr[y + 3 * 18] = t[16 + i + 1] + t[24 + i] + t[24 + i + 1]
        y += 4 * 18
      }
      gr[y + 0 * 18] = t[7]
      gr[y + 1 * 18] = t[16 + 7] + t[24 + 7]
      gr[y + 2 * 18] = t[8 + 7]
      gr[y + 3 * 18] = t[24 + 7]
    }
  }

  private fun scalePcm(sample: Float): Float = sample * (1f / 32768f)

  private fun synthPair(pcm: FloatArray, pcmOff: Int, nch: Int, z: FloatArray, zOffIn: Int) {
    var zOff = zOffIn
    var a: Float
    a = (z[zOff + 14 * 64] - z[zOff + 0]) * 29
    a += (z[zOff + 1 * 64] + z[zOff + 13 * 64]) * 213
    a += (z[zOff + 12 * 64] - z[zOff + 2 * 64]) * 459
    a += (z[zOff + 3 * 64] + z[zOff + 11 * 64]) * 2037
    a += (z[zOff + 10 * 64] - z[zOff + 4 * 64]) * 5153
    a += (z[zOff + 5 * 64] + z[zOff + 9 * 64]) * 6574
    a += (z[zOff + 8 * 64] - z[zOff + 6 * 64]) * 37489
    a += z[zOff + 7 * 64] * 75038
    pcm[pcmOff] = scalePcm(a)

    zOff += 2
    a = z[zOff + 14 * 64] * 104
    a += z[zOff + 12 * 64] * 1567
    a += z[zOff + 10 * 64] * 9727
    a += z[zOff + 8 * 64] * 64019
    a += z[zOff + 6 * 64] * -9975
    a += z[zOff + 4 * 64] * -45
    a += z[zOff + 2 * 64] * 146
    a += z[zOff + 0 * 64] * -5
    pcm[pcmOff + 16 * nch] = scalePcm(a)
  }

  @Suppress("LongMethod")
  private fun synth(gr: FloatArray, xlOff: Int, pcm: FloatArray, dstlOff: Int, nch: Int, lins: FloatArray, linsOff: Int) {
    val win = Mp3Tables.SYNTH_WIN
    val xrOff = xlOff + 576 * (nch - 1)
    val dstrOff = dstlOff + (nch - 1)
    val zlin = linsOff + 15 * 64
    var w = 0

    lins[zlin + 4 * 15] = gr[xlOff + 18 * 16]
    lins[zlin + 4 * 15 + 1] = gr[xrOff + 18 * 16]
    lins[zlin + 4 * 15 + 2] = gr[xlOff]
    lins[zlin + 4 * 15 + 3] = gr[xrOff]

    lins[zlin + 4 * 31] = gr[xlOff + 1 + 18 * 16]
    lins[zlin + 4 * 31 + 1] = gr[xrOff + 1 + 18 * 16]
    lins[zlin + 4 * 31 + 2] = gr[xlOff + 1]
    lins[zlin + 4 * 31 + 3] = gr[xrOff + 1]

    synthPair(pcm, dstrOff, nch, lins, linsOff + 4 * 15 + 1)
    synthPair(pcm, dstrOff + 32 * nch, nch, lins, linsOff + 4 * 15 + 64 + 1)
    synthPair(pcm, dstlOff, nch, lins, linsOff + 4 * 15)
    synthPair(pcm, dstlOff + 32 * nch, nch, lins, linsOff + 4 * 15 + 64)

    val a = synthA
    val bArr = synthB
    for (i in 14 downTo 0) {
      lins[zlin + 4 * i] = gr[xlOff + 18 * (31 - i)]
      lins[zlin + 4 * i + 1] = gr[xrOff + 18 * (31 - i)]
      lins[zlin + 4 * i + 2] = gr[xlOff + 1 + 18 * (31 - i)]
      lins[zlin + 4 * i + 3] = gr[xrOff + 1 + 18 * (31 - i)]
      lins[zlin + 4 * (i + 16)] = gr[xlOff + 1 + 18 * (1 + i)]
      lins[zlin + 4 * (i + 16) + 1] = gr[xrOff + 1 + 18 * (1 + i)]
      lins[zlin + 4 * (i - 16) + 2] = gr[xlOff + 18 * (1 + i)]
      lins[zlin + 4 * (i - 16) + 3] = gr[xrOff + 18 * (1 + i)]

      for (k in 0 until 8) {
        val w0 = win[w++]
        val w1 = win[w++]
        val vz = zlin + 4 * i - k * 64
        val vy = zlin + 4 * i - (15 - k) * 64
        when {
          k == 0 -> for (j in 0 until 4) {
            bArr[j] = lins[vz + j] * w1 + lins[vy + j] * w0
            a[j] = lins[vz + j] * w0 - lins[vy + j] * w1
          }
          k % 2 == 0 -> for (j in 0 until 4) {
            bArr[j] += lins[vz + j] * w1 + lins[vy + j] * w0
            a[j] += lins[vz + j] * w0 - lins[vy + j] * w1
          }
          else -> for (j in 0 until 4) {
            bArr[j] += lins[vz + j] * w1 + lins[vy + j] * w0
            a[j] += lins[vy + j] * w1 - lins[vz + j] * w0
          }
        }
      }

      pcm[dstrOff + (15 - i) * nch] = scalePcm(a[1])
      pcm[dstrOff + (17 + i) * nch] = scalePcm(bArr[1])
      pcm[dstlOff + (15 - i) * nch] = scalePcm(a[0])
      pcm[dstlOff + (17 + i) * nch] = scalePcm(bArr[0])
      pcm[dstrOff + (47 - i) * nch] = scalePcm(a[3])
      pcm[dstrOff + (49 + i) * nch] = scalePcm(bArr[3])
      pcm[dstlOff + (47 - i) * nch] = scalePcm(a[2])
      pcm[dstlOff + (49 + i) * nch] = scalePcm(bArr[2])
    }
  }

  private fun synthGranule(qmf: FloatArray, gr: FloatArray, nbands: Int, nch: Int, pcm: FloatArray, pcmOff: Int, lins: FloatArray) {
    for (i in 0 until nch) {
      dctII(gr, 576 * i, nbands)
    }

    qmf.copyInto(lins, 0, 0, 15 * 64)

    var i = 0
    while (i < nbands) {
      synth(gr, i, pcm, pcmOff + 32 * nch * i, nch, lins, i * 64)
      i += 2
    }
    if (nch == 1) {
      var k = 0
      while (k < 15 * 64) {
        qmf[k] = lins[nbands * 64 + k]
        k += 2
      }
    } else {
      lins.copyInto(qmf, 0, nbands * 64, nbands * 64 + 15 * 64)
    }
  }

  companion object {
    /** Interleaved float capacity [decodeFrame] may need: 1152 samples × 2 channels. */
    const val MAX_SAMPLES_PER_FRAME = 1152 * 2

    private const val HDR_SIZE = 4
    private const val MAX_FREE_FORMAT_FRAME_SIZE = 2304
    private const val MAX_FRAME_SYNC_MATCHES = 10
    private const val MAX_L3_FRAME_PAYLOAD_BYTES = MAX_FREE_FORMAT_FRAME_SIZE
    private const val MAX_BITRESERVOIR_BYTES = 511
    private const val SHORT_BLOCK_TYPE = 2
    private const val STOP_BLOCK_TYPE = 3
    private const val BITS_DEQUANTIZER_OUT = -1
    private const val MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210
    private const val MAX_SCFI = (MAX_SCF + 3) and 3.inv()

    private fun b(a: ByteArray, i: Int): Int = a[i].toInt() and 0xFF

    /** Huffman-path byte fetch: corrupt streams can legally overread; C reads garbage, we read 0. */
    private fun byteAt(a: ByteArray, i: Int): Int = if (i < a.size) a[i].toInt() and 0xFF else 0
  }
}
