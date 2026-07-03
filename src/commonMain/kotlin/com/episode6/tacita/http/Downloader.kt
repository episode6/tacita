package com.episode6.tacita.http

import com.episode6.tacita.FileAlreadyExistsException
import com.episode6.tacita.systemFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

internal class Downloader(
  private val httpClient: HttpClient = HttpClient(),
  private val fileSystem: FileSystem = systemFileSystem,
  // ad servers stitch different fills per client tier keyed on user-agent; pinning the
  // string this app has always sent keeps the served bytes (and ad-cut behavior) stable
  private val userAgent: String = DEFAULT_USER_AGENT,
) {

  suspend fun fetchString(url: String): String =
    httpClient.get(url) { header(HttpHeaders.UserAgent, userAgent) }.bodyAsText()

  /* emits @FloatRange(from = 0.0, to = 1.0) */
  fun downloadFile(url: String, outputFile: Path, overwrite: Boolean): Flow<Float> = flow {
    if (fileSystem.exists(outputFile)) {
      when {
        overwrite -> fileSystem.delete(outputFile)
        else      -> throw FileAlreadyExistsException(outputFile)
      }
    }

    outputFile.parent?.let { fileSystem.createDirectories(it) }

    emit(0f)

    fileSystem.sink(outputFile).buffer().use { sink ->
      httpClient.prepareGet(url) { header(HttpHeaders.UserAgent, userAgent) }.execute { response ->
        val size = response.contentLength() ?: -1L
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var bytesRead = 0L
        while (true) {
          val read = channel.readAvailable(chunk, 0, chunk.size)
          if (read == -1) break
          if (read > 0) {
            sink.write(chunk, 0, read)
            if (size > 0) {
              bytesRead += read
              emit((bytesRead.toDouble() / size.toDouble()).toFloat())
            }
          }
        }
        sink.flush()
      }
    }

    emit(1f)
  }.flowOn(Dispatchers.IO)
}


private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
private const val DEFAULT_USER_AGENT = "okhttp/4.12.0"
