package com.episode6.tacita

import okio.FileSystem

/**
 * The host filesystem. Okio declares [FileSystem.SYSTEM] separately per platform (there is no
 * common declaration covering jvm + native), so common code must go through this expect/actual.
 */
internal expect val systemFileSystem: FileSystem
