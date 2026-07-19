package com.episode6.tacita.audio

import com.episode6.tacita.AdFingerprintInfo
import com.episode6.tacita.systemFileSystem
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * The on-disk fingerprint store: a single flat binary file owned by tacita, holding every
 * [StoredAdFingerprint] a consumer has accumulated for one scope (consumers should keep
 * one store per feed — creatives are targeted per show, and per-show scoping avoids
 * cross-show false positives from shared network assets). Rewritten atomically on every
 * mutation; sizes are small (a 60s creative is ~40KB of fingerprint, so even hundreds of
 * confirmed ads stay in the low MBs).
 */
internal class AdFingerprintStoreFile(
  private val fileSystem: FileSystem = systemFileSystem,
) {

  /** Loads the store, or an empty list when [path] doesn't exist. Throws on a corrupt file. */
  fun read(path: Path): List<StoredAdFingerprint> {
    if (!fileSystem.exists(path)) return emptyList()
    return fileSystem.source(path).buffer().use { source ->
      val magic = source.readUtf8(MAGIC.length.toLong())
      if (magic != MAGIC) throw IOException("not a tacita fingerprint store: $path")
      val version = source.readInt()
      if (version != VERSION) throw IOException("unsupported fingerprint store version $version: $path")
      val count = source.readInt()
      if (count < 0 || count > MAX_FINGERPRINTS) throw IOException("corrupt fingerprint store (count=$count): $path")
      (0 until count).map {
        val provenance = when (val code = source.readByte().toInt()) {
          PROVENANCE_DIFF_PROVEN -> AdFingerprintInfo.Provenance.DIFF_PROVEN
          PROVENANCE_HUMAN_CONFIRMED -> AdFingerprintInfo.Provenance.HUMAN_CONFIRMED
          else -> throw IOException("corrupt fingerprint store (provenance=$code): $path")
        }
        val durationMs = source.readLong()
        val totalBytes = source.readLong()
        val blockCount = source.readInt()
        if (blockCount < 1 || blockCount > MAX_BLOCKS_PER_FINGERPRINT) {
          throw IOException("corrupt fingerprint store (blockCount=$blockCount): $path")
        }
        val hashes = LongArray(blockCount) { source.readLong() }
        val digests = (0 until blockCount).map { source.readByteString(DIGEST_BYTES) }
        StoredAdFingerprint(
          provenance = provenance,
          durationMs = durationMs,
          totalBytes = totalBytes,
          blockHashes = hashes,
          blockDigests = digests,
        )
      }
    }
  }

  /** Atomically replaces the store's contents with [fingerprints]. */
  fun write(path: Path, fingerprints: List<StoredAdFingerprint>) {
    path.parent?.let { fileSystem.createDirectories(it) }
    val tempPath = "$path.tmp".toPath()
    try {
      fileSystem.sink(tempPath).buffer().use { sink ->
        sink.writeUtf8(MAGIC)
        sink.writeInt(VERSION)
        sink.writeInt(fingerprints.size)
        fingerprints.forEach { fp ->
          sink.writeByte(
            when (fp.provenance) {
              AdFingerprintInfo.Provenance.DIFF_PROVEN -> PROVENANCE_DIFF_PROVEN
              AdFingerprintInfo.Provenance.HUMAN_CONFIRMED -> PROVENANCE_HUMAN_CONFIRMED
            },
          )
          sink.writeLong(fp.durationMs)
          sink.writeLong(fp.totalBytes)
          sink.writeInt(fp.blockHashes.size)
          fp.blockHashes.forEach { sink.writeLong(it) }
          fp.blockDigests.forEach { sink.write(it) }
        }
      }
      fileSystem.delete(path, mustExist = false) // atomicMove onto an existing target can throw on windows
      fileSystem.atomicMove(tempPath, path)
    } finally {
      fileSystem.delete(tempPath, mustExist = false)
    }
  }

  /**
   * Adds [fingerprints], deduping by content [StoredAdFingerprint.id]. A re-added creative
   * keeps its strongest provenance (HUMAN_CONFIRMED is never downgraded to DIFF_PROVEN).
   * Returns the number of fingerprints actually added or upgraded.
   */
  fun add(path: Path, fingerprints: List<StoredAdFingerprint>): Int {
    if (fingerprints.isEmpty()) return 0
    val existing = read(path).associateBy { it.id }.toMutableMap()
    var changed = 0
    fingerprints.forEach { fp ->
      val current = existing[fp.id]
      val upgrade = current != null &&
        current.provenance == AdFingerprintInfo.Provenance.DIFF_PROVEN &&
        fp.provenance == AdFingerprintInfo.Provenance.HUMAN_CONFIRMED
      if (current == null || upgrade) {
        existing[fp.id] = fp
        changed++
      }
    }
    if (changed > 0) write(path, existing.values.toList())
    return changed
  }

  /** Removes the fingerprint with [id]; returns false when it wasn't present. */
  fun remove(path: Path, id: String): Boolean {
    val existing = read(path)
    val remaining = existing.filterNot { it.id == id }
    if (remaining.size == existing.size) return false
    write(path, remaining)
    return true
  }

  private companion object {
    const val MAGIC = "tacita-fp"
    const val VERSION = 1
    const val DIGEST_BYTES = 32L
    const val PROVENANCE_DIFF_PROVEN = 0
    const val PROVENANCE_HUMAN_CONFIRMED = 1
    // sanity bounds so a corrupt count can't drive unbounded allocation
    const val MAX_FINGERPRINTS = 100_000
    const val MAX_BLOCKS_PER_FINGERPRINT = 1_000_000
  }
}
