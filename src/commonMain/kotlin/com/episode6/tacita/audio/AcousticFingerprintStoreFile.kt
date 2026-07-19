package com.episode6.tacita.audio

import com.episode6.tacita.AcousticFingerprintInfo
import com.episode6.tacita.AdFingerprintInfo
import com.episode6.tacita.systemFileSystem
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * An [AcousticFingerprint] in a store, carrying per-feed attributions instead of the byte
 * layer's single provenance: level-invariant matching is exactly where cross-feed ad
 * campaigns pay off, so acoustic stores are designed to be shared globally across feeds —
 * which requires knowing *which feed* earned each fingerprint its place, so one feed's
 * evidence can be revoked without deleting another feed's confirmation
 * (docs/ALGORITHM.md "Store scoping", 2026-07-19).
 */
internal class StoredAcousticFingerprint(
  val fingerprint: AcousticFingerprint,
  /** feedId -> the strongest provenance that feed has recorded for this creative. */
  val attributions: Map<String, AdFingerprintInfo.Provenance>,
) {
  init {
    require(attributions.isNotEmpty()) { "a stored acoustic fingerprint needs at least one feed attribution" }
  }

  val id: String get() = fingerprint.id

  val info: AcousticFingerprintInfo
    get() = AcousticFingerprintInfo(id = id, durationMs = fingerprint.durationMs, attributions = attributions)

  override fun equals(other: Any?): Boolean =
    other is StoredAcousticFingerprint && other.id == id && other.attributions == attributions

  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "StoredAcousticFingerprint(id=${id.take(12)}, attributions=$attributions)"
}

/**
 * The on-disk acoustic fingerprint store: a single flat binary file owned by tacita,
 * holding every [StoredAcousticFingerprint] a consumer has accumulated. Unlike the byte
 * layer's [AdFingerprintStoreFile] (recommended per-feed), one acoustic store is meant to
 * be shared across every feed the consumer follows — matching survives re-encoding, so a
 * campaign confirmed in one show is findable in any other. Rewritten atomically on every
 * mutation; sizes stay small (a 60s creative is ~8k landmarks ≈ 64KB).
 */
internal class AcousticFingerprintStoreFile(
  private val fileSystem: FileSystem = systemFileSystem,
) {

  /** Loads the store, or an empty list when [path] doesn't exist. Throws on a corrupt file. */
  fun read(path: Path): List<StoredAcousticFingerprint> {
    if (!fileSystem.exists(path)) return emptyList()
    return fileSystem.source(path).buffer().use { source ->
      val magic = source.readUtf8(MAGIC.length.toLong())
      if (magic != MAGIC) throw IOException("not a tacita acoustic fingerprint store: $path")
      val version = source.readInt()
      if (version != VERSION) throw IOException("unsupported acoustic fingerprint store version $version: $path")
      val count = source.readInt()
      if (count < 0 || count > MAX_FINGERPRINTS) throw IOException("corrupt acoustic fingerprint store (count=$count): $path")
      (0 until count).map {
        val durationMs = source.readLong()
        val landmarkCount = source.readInt()
        if (landmarkCount < 1 || landmarkCount > MAX_LANDMARKS_PER_FINGERPRINT) {
          throw IOException("corrupt acoustic fingerprint store (landmarkCount=$landmarkCount): $path")
        }
        val hashes = IntArray(landmarkCount) { source.readInt() }
        val frames = IntArray(landmarkCount) { source.readInt() }
        val attributionCount = source.readInt()
        if (attributionCount < 1 || attributionCount > MAX_ATTRIBUTIONS_PER_FINGERPRINT) {
          throw IOException("corrupt acoustic fingerprint store (attributionCount=$attributionCount): $path")
        }
        val attributions = (0 until attributionCount).associate {
          val feedIdBytes = source.readInt()
          if (feedIdBytes < 1 || feedIdBytes > MAX_FEED_ID_BYTES) {
            throw IOException("corrupt acoustic fingerprint store (feedIdBytes=$feedIdBytes): $path")
          }
          val feedId = source.readUtf8(feedIdBytes.toLong())
          val provenance = when (val code = source.readByte().toInt()) {
            PROVENANCE_DIFF_PROVEN -> AdFingerprintInfo.Provenance.DIFF_PROVEN
            PROVENANCE_HUMAN_CONFIRMED -> AdFingerprintInfo.Provenance.HUMAN_CONFIRMED
            else -> throw IOException("corrupt acoustic fingerprint store (provenance=$code): $path")
          }
          feedId to provenance
        }
        StoredAcousticFingerprint(
          fingerprint = AcousticFingerprint(durationMs = durationMs, hashes = hashes, frames = frames),
          attributions = attributions,
        )
      }
    }
  }

  /** Atomically replaces the store's contents with [fingerprints]. */
  fun write(path: Path, fingerprints: List<StoredAcousticFingerprint>) {
    path.parent?.let { fileSystem.createDirectories(it) }
    val tempPath = "$path.tmp".toPath()
    try {
      fileSystem.sink(tempPath).buffer().use { sink ->
        sink.writeUtf8(MAGIC)
        sink.writeInt(VERSION)
        sink.writeInt(fingerprints.size)
        fingerprints.forEach { fp ->
          sink.writeLong(fp.fingerprint.durationMs)
          sink.writeInt(fp.fingerprint.hashes.size)
          fp.fingerprint.hashes.forEach { sink.writeInt(it) }
          fp.fingerprint.frames.forEach { sink.writeInt(it) }
          sink.writeInt(fp.attributions.size)
          fp.attributions.forEach { (feedId, provenance) ->
            val feedIdBytes = feedId.encodeToByteArray()
            sink.writeInt(feedIdBytes.size)
            sink.write(feedIdBytes)
            sink.writeByte(
              when (provenance) {
                AdFingerprintInfo.Provenance.DIFF_PROVEN -> PROVENANCE_DIFF_PROVEN
                AdFingerprintInfo.Provenance.HUMAN_CONFIRMED -> PROVENANCE_HUMAN_CONFIRMED
              },
            )
          }
        }
      }
      fileSystem.delete(path, mustExist = false) // atomicMove onto an existing target can throw on windows
      fileSystem.atomicMove(tempPath, path)
    } finally {
      fileSystem.delete(tempPath, mustExist = false)
    }
  }

  /**
   * Adds [fingerprints], merging by content [StoredAcousticFingerprint.id]: a re-added
   * creative unions its feed attributions with the stored ones, and within a feed the
   * strongest provenance wins (HUMAN_CONFIRMED is never downgraded to DIFF_PROVEN).
   * Returns the number of fingerprints actually added or changed.
   */
  fun add(path: Path, fingerprints: List<StoredAcousticFingerprint>): Int {
    if (fingerprints.isEmpty()) return 0
    val existing = read(path).associateBy { it.id }.toMutableMap()
    var changed = 0
    fingerprints.forEach { fp ->
      val current = existing[fp.id]
      if (current == null) {
        existing[fp.id] = fp
        changed++
        return@forEach
      }
      val merged = mergeAttributions(current.attributions, fp.attributions)
      if (merged != current.attributions) {
        existing[fp.id] = StoredAcousticFingerprint(fingerprint = current.fingerprint, attributions = merged)
        changed++
      }
    }
    if (changed > 0) write(path, existing.values.toList())
    return changed
  }

  /** Removes the fingerprint with [id] outright, for every feed; returns false when it wasn't present. */
  fun remove(path: Path, id: String): Boolean {
    val existing = read(path)
    val remaining = existing.filterNot { it.id == id }
    if (remaining.size == existing.size) return false
    write(path, remaining)
    return true
  }

  private fun mergeAttributions(
    current: Map<String, AdFingerprintInfo.Provenance>,
    added: Map<String, AdFingerprintInfo.Provenance>,
  ): Map<String, AdFingerprintInfo.Provenance> {
    val merged = current.toMutableMap()
    added.forEach { (feedId, provenance) ->
      val existing = merged[feedId]
      if (existing == null || (existing == AdFingerprintInfo.Provenance.DIFF_PROVEN && provenance == AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)) {
        merged[feedId] = provenance
      }
    }
    return merged
  }

  private companion object {
    const val MAGIC = "tacita-afp"
    const val VERSION = 1
    const val PROVENANCE_DIFF_PROVEN = 0
    const val PROVENANCE_HUMAN_CONFIRMED = 1
    // sanity bounds so a corrupt count can't drive unbounded allocation
    const val MAX_FINGERPRINTS = 100_000
    const val MAX_LANDMARKS_PER_FINGERPRINT = 1_000_000
    const val MAX_ATTRIBUTIONS_PER_FINGERPRINT = 10_000
    const val MAX_FEED_ID_BYTES = 4096
  }
}
