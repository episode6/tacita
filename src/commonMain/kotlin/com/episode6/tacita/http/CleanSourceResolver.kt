package com.episode6.tacita.http

import io.ktor.http.Url
import kotlin.math.abs

/**
 * Tries to locate a copy of the episode that provably contains no injected ads, so it can
 * be served directly instead of running the diff pipeline (2026-07-04 field research: some
 * hosts now inject sticky fill on every tier, which blinds an immediate same-tier
 * reference — but a genuinely clean copy is often discoverable out-of-band).
 *
 * Candidates, in order:
 *  1. the episode url itself with the pinned user-agent (hosts like Acast serve the
 *     canonical stitch to the app tier — skipping the reference download saves bandwidth),
 *  2. a `fallback_url` query param leaked in the resolved redirect chain (Audioboom's
 *     CloudFront variant urls carry a signed `media_type=static` link to the publisher's
 *     original upload),
 *  3. the episode url fetched as a bot-tier client (Simplecast serves the clean canonical
 *     to curl/wget-style user-agents).
 *
 * A candidate only wins if its probed content-length matches what the *feed* declares for
 * the episode ([resolve]'s `declaredEnclosureBytes` / `expectedDurationSeconds`) — the ad
 * server's own claims are never trusted to decide cleanliness. With no feed-declared
 * expectation there is nothing to validate against and resolution is skipped entirely.
 * A false reject just falls back to the diff pipeline; the tolerances below are chosen so
 * a copy carrying even a single ad creative (~10s ≈ 160KB at 128kbps) cannot validate.
 */
internal class CleanSourceResolver(
  private val downloader: Downloader,
  private val log: (String) -> Unit = {},
  private val botUserAgents: List<String> = DEFAULT_BOT_USER_AGENTS,
) {

  /** A serving verified to match the feed-declared size/duration. */
  data class CleanSource(
    val url: String,
    /** null = the pinned default user-agent. */
    val userAgent: String?,
    /** The probed size; the downloaded file must match it (guards against truncation). */
    val contentLength: Long,
  )

  suspend fun resolve(
    url: String,
    declaredEnclosureBytes: Long?,
    expectedDurationSeconds: Long?,
  ): CleanSource? {
    if ((declaredEnclosureBytes ?: 0) <= 0 && (expectedDurationSeconds ?: 0) <= 0) return null

    fun validated(candidateUrl: String, userAgent: String?, probe: Downloader.ProbeResult?): CleanSource? {
      val length = probe?.contentLength ?: return null
      if (!isCleanLength(length, declaredEnclosureBytes, expectedDurationSeconds)) return null
      return CleanSource(candidateUrl, userAgent, length)
    }

    val pinned = probeQuietly(url, userAgent = null)
    pinned?.finalUrl?.let(::logDaiMetadata)
    validated(url, null, pinned)?.let {
      log("CleanSourceResolver: pinned-tier serving is already clean (${it.contentLength} bytes)")
      return it
    }

    pinned?.finalUrl?.let(::fallbackUrlFrom)?.let { fallback ->
      validated(fallback, null, probeQuietly(fallback, userAgent = null))?.let {
        log("CleanSourceResolver: using static fallback from redirect chain (${it.contentLength} bytes)")
        return it
      }
    }

    for (userAgent in botUserAgents) {
      validated(url, userAgent, probeQuietly(url, userAgent))?.let {
        log("CleanSourceResolver: bot-tier serving via \"$userAgent\" is clean (${it.contentLength} bytes)")
        return it
      }
    }
    return null
  }

  // a probe failure only means this candidate is unavailable, never that the download fails
  private suspend fun probeQuietly(url: String, userAgent: String?): Downloader.ProbeResult? =
    try {
      downloader.probe(url, userAgent)
    } catch (t: Throwable) {
      log("CleanSourceResolver: probe failed for $url: ${t.message}")
      null
    }

  private fun fallbackUrlFrom(finalUrl: String): String? = runCatching {
    Url(finalUrl).parameters["fallback_url"]
  }.getOrNull()?.takeIf { it.startsWith("http") }

  // Audioboom leaks DAI metadata in its resolved urls: m=[slot positions ms],
  // o/al=original duration ms, ab=bitrate kbps. Diagnostics only for now — a future
  // strategy could turn slot positions into cut hints (see docs/ALGORITHM.md).
  private fun logDaiMetadata(finalUrl: String) {
    val params = runCatching { Url(finalUrl).parameters }.getOrNull() ?: return
    val found = DAI_METADATA_KEYS.mapNotNull { key -> params[key]?.let { "$key=$it" } }
    if (found.isNotEmpty()) log("CleanSourceResolver: dai metadata in resolved url: $found")
  }
}

private fun isCleanLength(bytes: Long, declaredEnclosureBytes: Long?, expectedDurationSeconds: Long?): Boolean {
  declaredEnclosureBytes?.takeIf { it > 0 }?.let { declared ->
    // 0.5% covers ID3-tag variance between servings, capped well below one ad creative
    val tolerance = minOf(declared / 200, 100_000L).coerceAtLeast(4_096L)
    if (abs(bytes - declared) <= tolerance) return true
  }
  expectedDurationSeconds?.takeIf { it >= 60 }?.let { duration ->
    // a clean CBR file implies a standard mp3 bitrate plus a little ID3 overhead;
    // injected fill pushes the implied rate well past it, truncation drops it below
    val impliedBps = bytes * 8.0 / duration
    val nearest = STANDARD_MP3_BITRATES_BPS.minBy { abs(it - impliedBps) }
    if (impliedBps >= nearest * 0.999 && impliedBps <= nearest * 1.015) return true
  }
  return false
}

// tiers observed to receive the clean canonical: curl/wget/googlebot-style clients
// (Simplecast verified 2026-07-04; Acast observed 2026-07-02)
private val DEFAULT_BOT_USER_AGENTS = listOf("curl/8.5.0", "Wget/1.21.4")

private val DAI_METADATA_KEYS = listOf("m", "o", "al", "ab", "ao")

private val STANDARD_MP3_BITRATES_BPS = listOf(
  16_000, 24_000, 32_000, 40_000, 48_000, 56_000, 64_000, 80_000, 96_000,
  112_000, 128_000, 160_000, 192_000, 224_000, 256_000, 320_000,
)
