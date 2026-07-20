package com.episode6.tacita

import okio.FileSystem
import okio.Path

internal actual val systemTempDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
