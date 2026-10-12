package com.mutetube.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resolves playable stream URLs for a YouTube video on the device (NewPipeExtractor). */
object YouTubeStreams {

    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val CACHE_MS = 60 * 60 * 1000L // stream URLs live ~6h; refresh well before

    /** One HTTP client for extraction and playback, so both use the same connection/IP. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, StreamInfo>>()

    @Volatile private var initialized = false

    private fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader(http), Localization("en", "US"))
                initialized = true
            }
        }
    }

    /** Thrown with a message that can be shown to the student as-is. */
    class StreamException(message: String, val retryable: Boolean, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Blocking: call from a background thread. Retries transient failures with backoff.
     * [fresh] skips the cache (used when a stream URL stopped working).
     */
    @Throws(StreamException::class)
    fun load(videoId: String, fresh: Boolean = false): StreamInfo {
        init()
        if (!fresh) {
            cache[videoId]?.let { (time, info) ->
                if (System.currentTimeMillis() - time < CACHE_MS) return info
            }
        }
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) { break }
            }
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
                cache[videoId] = System.currentTimeMillis() to info
                return info
            } catch (t: Throwable) {
                last = t
                if (!isTransient(t)) break
            }
        }
        throw toStreamException(last)
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun isTransient(t: Throwable): Boolean = when (t) {
        is ContentNotAvailableException, is ReCaptchaException -> false
        is IOException -> true
        else -> true // parsing hiccups are often temporary on YouTube's side
    }

    private fun toStreamException(t: Throwable?): StreamException = when (t) {
        is PrivateContentException ->
            StreamException("This video is private and can't be played.", false, t)
        is AgeRestrictedContentException ->
            StreamException("This video is age-restricted and can't be played in the app.", false, t)
        is GeographicRestrictionException ->
            StreamException("This video isn't available in your country.", false, t)
        is PaidContentException ->
            StreamException("This video requires a paid membership.", false, t)
        is SignInConfirmNotBotException, is ReCaptchaException ->
            StreamException("The video server is temporarily limiting this network. Try again later or switch Wi-Fi / mobile data.", true, t)
        is ContentNotAvailableException ->
            StreamException("This video is unavailable or was removed.", false, t)
        is InterruptedIOException, is IOException ->
            StreamException("No internet connection. Check your network and retry.", true, t)
        else ->
            StreamException("Couldn't load this video right now. Please retry.", true, t)
    }

    /** User-Agent YouTube expects when fetching a stream URL issued for a given client. */
    fun userAgentFor(url: String): String = try {
        when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            // HLS playlists/segments carry the client in the path, e.g. ".../c/IOS/..."
            url.contains("/c/IOS/") -> YoutubeParsingHelper.getIosUserAgent(null)
            url.contains("/c/VISIONOS/") -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            url.contains("/c/ANDROID") -> YoutubeParsingHelper.getAndroidUserAgent(null)
            else -> BROWSER_UA
        }
    } catch (_: Throwable) {
        BROWSER_UA
    }

    /** Extra headers web-client stream URLs need. */
    fun extraHeadersFor(url: String): Map<String, String> = try {
        if (YoutubeParsingHelper.isWebStreamingUrl(url) || YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url)) {
            mapOf("Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
        } else emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .header("User-Agent", BROWSER_UA)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            val body = request.dataToSend()?.toRequestBody()
            builder.method(request.httpMethod(), body)
            client.newCall(builder.build()).execute().use { res ->
                if (res.code == 429) throw ReCaptchaException("rate limited", request.url())
                return Response(res.code, res.message, res.headers.toMultimap(), res.body?.string(), res.request.url.toString())
            }
        }
    }
}
