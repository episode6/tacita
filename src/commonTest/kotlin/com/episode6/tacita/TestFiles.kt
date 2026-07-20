package com.episode6.tacita

import okio.Path
import kotlin.random.Random

// okio has no common declaration of FileSystem.SYSTEM_TEMPORARY_DIRECTORY (same story as systemFileSystem)
internal expect val systemTempDirectory: Path

internal fun testTempDir(prefix: String): Path =
  (systemTempDirectory / "$prefix-${randomSuffix()}").also { systemFileSystem.createDirectories(it) }

internal fun testTempFile(prefix: String, bytes: ByteArray = ByteArray(0)): Path =
  (systemTempDirectory / "$prefix-${randomSuffix()}.tmp").also { it.writeBytes(bytes) }

internal fun Path.writeBytes(bytes: ByteArray) {
  systemFileSystem.write(this) { write(bytes) }
}

internal fun Path.readBytes(): ByteArray = systemFileSystem.read(this) { readByteArray() }

internal fun Path.exists(): Boolean = systemFileSystem.exists(this)

private fun randomSuffix(): String = Random.nextLong().toULong().toString(16)
