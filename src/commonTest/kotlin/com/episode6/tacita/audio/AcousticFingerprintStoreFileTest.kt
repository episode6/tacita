package com.episode6.tacita.audio

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.episode6.tacita.AdFingerprintInfo.Provenance
import com.episode6.tacita.testTempDir
import com.episode6.tacita.writeBytes
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.test.Test

class AcousticFingerprintStoreFileTest {

  private val store = AcousticFingerprintStoreFile()
  private val dir = testTempDir("afp-store-test")
  private val path: Path = dir / "ads.tacita-afp"

  /** A synthetic constellation — the codec doesn't care that no audio produced it. */
  private fun fingerprint(seed: Int, landmarks: Int = 100, durationMs: Long = 8_000): AcousticFingerprint {
    val random = Random(seed)
    val frames = IntArray(landmarks)
    for (i in 1 until landmarks) frames[i] = frames[i - 1] + random.nextInt(3)
    return AcousticFingerprint(
      durationMs = durationMs,
      hashes = IntArray(landmarks) { random.nextInt(1 shl 24) },
      frames = frames,
    )
  }

  private fun stored(seed: Int, vararg attributions: Pair<String, Provenance>): StoredAcousticFingerprint =
    StoredAcousticFingerprint(fingerprint(seed), attributions.toMap())

  @Test fun `reading a missing store returns empty`() {
    assertThat(store.read(path)).isEmpty()
  }

  @Test fun `round-trips fingerprints with their attributions`() {
    val fps = listOf(
      stored(1, "feed-a" to Provenance.DIFF_PROVEN),
      stored(2, "feed-a" to Provenance.HUMAN_CONFIRMED, "https://feeds.example.com/other?id=✓" to Provenance.DIFF_PROVEN),
    )

    store.write(path, fps)
    val loaded = store.read(path)

    assertThat(loaded).hasSize(2)
    loaded.zip(fps).forEach { (actual, expected) ->
      assertThat(actual.id).isEqualTo(expected.id)
      assertThat(actual.fingerprint.durationMs).isEqualTo(expected.fingerprint.durationMs)
      assertThat(actual.fingerprint.hashes.toList()).isEqualTo(expected.fingerprint.hashes.toList())
      assertThat(actual.fingerprint.frames.toList()).isEqualTo(expected.fingerprint.frames.toList())
      assertThat(actual.attributions).isEqualTo(expected.attributions)
    }
  }

  @Test fun `add dedupes by content id`() {
    assertThat(store.add(path, listOf(stored(1, "feed-a" to Provenance.DIFF_PROVEN)))).isEqualTo(1)
    assertThat(store.add(path, listOf(stored(1, "feed-a" to Provenance.DIFF_PROVEN)))).isEqualTo(0)

    assertThat(store.read(path)).hasSize(1)
  }

  @Test fun `add merges a second feed's attribution onto an existing creative`() {
    store.add(path, listOf(stored(1, "feed-a" to Provenance.DIFF_PROVEN)))

    val changed = store.add(path, listOf(stored(1, "feed-b" to Provenance.HUMAN_CONFIRMED)))

    assertThat(changed).isEqualTo(1)
    assertThat(store.read(path).single().attributions).isEqualTo(
      mapOf("feed-a" to Provenance.DIFF_PROVEN, "feed-b" to Provenance.HUMAN_CONFIRMED),
    )
  }

  @Test fun `add upgrades a feed's provenance but never downgrades it`() {
    store.add(path, listOf(stored(1, "feed-a" to Provenance.DIFF_PROVEN)))

    val upgraded = store.add(path, listOf(stored(1, "feed-a" to Provenance.HUMAN_CONFIRMED)))
    assertThat(upgraded, name = "upgrade counts as a change").isEqualTo(1)
    assertThat(store.read(path).single().attributions).isEqualTo(mapOf("feed-a" to Provenance.HUMAN_CONFIRMED))

    val downgraded = store.add(path, listOf(stored(1, "feed-a" to Provenance.DIFF_PROVEN)))
    assertThat(downgraded, name = "downgrade is a no-op").isEqualTo(0)
    assertThat(store.read(path).single().attributions).isEqualTo(mapOf("feed-a" to Provenance.HUMAN_CONFIRMED))
  }

  @Test fun `remove deletes by id across all feeds`() {
    val fp = stored(1, "feed-a" to Provenance.HUMAN_CONFIRMED, "feed-b" to Provenance.HUMAN_CONFIRMED)
    store.add(path, listOf(fp, stored(2, "feed-a" to Provenance.DIFF_PROVEN)))

    assertThat(store.remove(path, fp.id)).isTrue()
    assertThat(store.read(path)).hasSize(1)
    assertThat(store.remove(path, fp.id), name = "second remove finds nothing").isFalse()
  }

  @Test fun `a corrupt store throws instead of returning garbage`() {
    path.writeBytes("not an acoustic fingerprint store".encodeToByteArray())

    assertFailure { store.read(path) }.isInstanceOf(IOException::class)
  }

  @Test fun `a byte-layer store is rejected by magic`() {
    // same directory conventions, different codec — mixing the two must fail loudly
    AdFingerprintStoreFile().write(path, emptyList())

    assertFailure { store.read(path) }.isInstanceOf(IOException::class)
  }
}
