package com.episode6.tacita.http

import com.episode6.tacita.FileAlreadyExistsException
import com.episode6.tacita.systemFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.FileSystem
import okio.IOException
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

  /** What a GET at [url] would serve, resolved without downloading the body. */
  data class ProbeResult(
    /** The url after all redirects — ad-serving chains leak metadata in its query params. */
    val finalUrl: String,
    val contentLength: Long?,
  )

  /**
   * Resolves [url]'s redirect chain and reports the served content-length without paying
   * for the body (a single-byte Range request; servers that ignore Range still only cost
   * the response headers because the body is never read).
   */
  suspend fun probe(url: String, userAgent: String? = null): ProbeResult? =
    httpClient.prepareGet(url) {
      header(HttpHeaders.UserAgent, userAgent ?: this@Downloader.userAgent)
      header(HttpHeaders.Range, "bytes=0-0")
    }.execute { response ->
      if (!response.status.isSuccess()) return@execute null
      val contentRange = response.headers[HttpHeaders.ContentRange]
      ProbeResult(
        finalUrl = response.request.url.toString(),
        contentLength = when {
          // 206 responses carry the full size in "bytes 0-0/<total>"
          contentRange != null -> contentRange.substringAfterLast('/').toLongOrNull()
          else                 -> response.contentLength()
        },
      )
    }

  /* emits @FloatRange(from = 0.0, to = 1.0) */
  fun downloadFile(url: String, outputFile: Path, overwrite: Boolean, userAgent: String? = null): Flow<Float> = flow {
    if (fileSystem.exists(outputFile)) {
      when {
        overwrite -> fileSystem.delete(outputFile)
        else      -> throw FileAlreadyExistsException(outputFile)
      }
    }

    outputFile.parent?.let { fileSystem.createDirectories(it) }

    emit(0f)

    try {
      httpClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, userAgent ?: this@Downloader.userAgent)
      }.execute { response ->
        if (!response.status.isSuccess()) {
          throw IOException("GET $url failed with status ${response.status}")
        }
        fileSystem.sink(outputFile).buffer().use { sink ->
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
    } catch (t: Throwable) {
      // never leave a partial file behind: a later overwrite run would promote it to
      // become the reference copy
      fileSystem.delete(outputFile, mustExist = false)
      throw t
    }

    emit(1f)
  }.flowOn(Dispatchers.IO)
}


private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
private const val DEFAULT_USER_AGENT = "okhttp/4.12.0"
