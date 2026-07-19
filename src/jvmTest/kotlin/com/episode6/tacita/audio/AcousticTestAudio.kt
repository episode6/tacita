package com.episode6.tacita.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shared audio-synthesis helpers for the acoustic-layer tests: deterministic
 * speech-shaped PCM plus in-test mp3 encoding through jump3r (the pure-java LAME port),
 * so tests can re-create the exact serving shapes the acoustic layer exists for —
 * per-episode re-encodes at different bitrates, sample rates and gains.
 */

/**
 * Deterministic speech-shaped test audio: 250ms blocks of three random tones in the
 * 200-4000Hz band with a 10ms noise transient at each block start — enough moving
 * spectral structure for a distinctive peak constellation, distinct per seed.
 */
internal fun synth(seed: Int, seconds: Double, hz: Int = 44100): FloatArray {
  val random = Random(seed)
  val total = (seconds * hz).toInt()
  val out = FloatArray(total)
  val blockLen = hz / 4
  var pos = 0
  while (pos < total) {
    val len = min(blockLen, total - pos)
    val freqs = DoubleArray(3) { 200 + random.nextDouble() * 3800 }
    val amps = DoubleArray(3) { 0.1 + random.nextDouble() * 0.1 }
    for (i in 0 until len) {
      var v = 0.0
      for (t in 0 until 3) v += amps[t] * sin(2.0 * PI * freqs[t] * i / hz)
      if (i < hz / 100) v += (random.nextDouble() * 2 - 1) * 0.15
      out[pos + i] = v.toFloat()
    }
    pos += len
  }
  return out
}

internal fun encodeMp3(pcm: FloatArray, kbps: Int, resampleKHz: String? = null, scale: Double? = null): ByteArray {
  val wav = tempAudioFile(".wav").apply { writeBytes(monoWav(pcm, hz = 44100)) }
  val mp3 = tempAudioFile(".mp3")
  val args = buildList {
    add("--quiet")
    add("-b")
    add(kbps.toString())
    resampleKHz?.let { add("--resample"); add(it) }
    scale?.let { add("--scale"); add(it.toString()) }
    add(wav.absolutePath)
    add(mp3.absolutePath)
  }
  val code = de.sciss.jump3r.Main().run(args.toTypedArray())
  check(code == 0) { "jump3r failed with exit code $code (args: $args)" }
  return mp3.readBytes()
}

internal fun monoWav(pcm: FloatArray, hz: Int): ByteArray {
  val dataBytes = pcm.size * 2
  val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
  bytes.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
  bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
  bytes.putInt(hz).putInt(hz * 2).putShort(2).putShort(16)
  bytes.put("data".toByteArray()).putInt(dataBytes)
  pcm.forEach { bytes.putShort((it.coerceIn(-1f, 32767f / 32768f) * 32768).toInt().toShort()) }
  return bytes.array()
}

internal fun tempAudioFile(suffix: String): File =
  File.createTempFile("acoustic-test", suffix).apply { deleteOnExit() }
