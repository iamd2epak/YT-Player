package com.mutetube.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * HTTP data source for YouTube streams:
 *  - sends the User-Agent / headers matching the client the URL was issued for,
 *  - requests large progressive files in bounded chunks (YouTube throttles or rejects
 *    huge open-ended ranges) while exposing one continuous stream to the player.
 */
@OptIn(UnstableApi::class)
class YouTubeDataSource private constructor(private val upstream: DataSource) : DataSource {

    class Factory : DataSource.Factory {
        private val okHttp = OkHttpDataSource.Factory(YouTubeStreams.http)
        override fun createDataSource(): DataSource = YouTubeDataSource(okHttp.createDataSource())
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L        // absolute byte offset of the next byte to read
    private var end = C.LENGTH_UNSET.toLong() // absolute end (exclusive), or unset
    private var chunkEnd = 0L        // absolute end of the currently opened chunk

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers["User-Agent"] = YouTubeStreams.userAgentFor(url)
        headers.putAll(YouTubeStreams.extraHeadersFor(url))
        val withHeaders = dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
        spec = withHeaders

        // Only whole progressive files (googlevideo /videoplayback?...) get chunked.
        // (HLS segments use path-style URLs like /videoplayback/id/.../sq/12 and are left alone.)
        chunked = dataSpec.uri.host?.endsWith("googlevideo.com") == true &&
            dataSpec.uri.path == "/videoplayback"
        if (!chunked) return upstream.open(withHeaders)

        position = withHeaders.position
        end = if (withHeaders.length != C.LENGTH_UNSET.toLong()) position + withHeaders.length else C.LENGTH_UNSET.toLong()
        openChunk()

        // Learn the full size from "Content-Range: bytes a-b/total" if we don't know the end.
        if (end == C.LENGTH_UNSET.toLong()) {
            val total = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfterLast('/')?.toLongOrNull()
            if (total != null) end = total
        }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - position
    }

    private fun openChunk() {
        val base = spec!!
        var length = CHUNK
        if (end != C.LENGTH_UNSET.toLong()) length = minOf(length, end - position)
        val chunkSpec = base.buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        val opened = upstream.open(chunkSpec)
        chunkEnd = position + if (opened == C.LENGTH_UNSET.toLong()) length else opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT

        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // Current chunk finished: continue with the next one if the file isn't done.
            val more = if (end != C.LENGTH_UNSET.toLong()) position < end else position >= chunkEnd
            if (!more) return C.RESULT_END_OF_INPUT
            upstream.close()
            try {
                openChunk()
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) return C.RESULT_END_OF_INPUT // file ended exactly at chunk edge
                throw e
            }
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        }
        position += read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }
}
