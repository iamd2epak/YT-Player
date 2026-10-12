package com.iamd2epak.ytplayer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Downloads one job: fetches the stream file(s) in resumable chunks, muxes video + audio
 * into one MP4 (or remuxes audio to M4A) and saves it to the shared Movies / Music folder.
 * Blocking: runs on the download worker thread.
 */
class DownloadEngine(
    private val context: Context,
    private val job: Downloads.Job,
    private val onProgress: () -> Unit,
) {
    class CancelledException : Exception()
    class DownloadException(message: String) : Exception(message)

    private class Part(val isVideo: Boolean, val itag: Int, val trackId: String?, val file: File) {
        var url = ""
        var size = -1L   // exact size once known
        var estimate = 0L
    }

    companion object {
        private const val CHUNK = 8L * 1024 * 1024
        private const val MAX_FAILURES = 8
        const val FOLDER = "MuteAd Tube"

        fun tempRoot(context: Context) = File(context.filesDir, "dl_tmp")
    }

    private val dir = File(tempRoot(context), job.id.toString())
    private var refreshes = 0

    fun run() {
        dir.mkdirs()
        val info = YouTubeStreams.load(job.videoId) // StreamException carries a readable message
        val opt = job.option
        val parts = buildList {
            if (opt.videoItag >= 0) add(Part(true, opt.videoItag, null, File(dir, "video")))
            if (opt.audioItag >= 0) add(Part(false, opt.audioItag, opt.audioTrackId, File(dir, "audio")))
        }
        if (parts.isEmpty()) throw DownloadException("Nothing to download.")
        parts.forEach { resolveUrl(it, info) }
        if (opt.sizeBytes > 0) parts.forEach { it.estimate = opt.sizeBytes / parts.size }

        // Room for the downloaded parts plus the final file.
        val need = opt.sizeBytes * 2 + 20L * 1024 * 1024
        if (opt.sizeBytes > 0 && freeSpace(dir) < need) {
            throw DownloadException("Not enough storage. Free up ${Downloads.formatSize(need)} and try again.")
        }

        for (part in parts) fetch(part, parts)

        job.status = Downloads.Status.SAVING
        onProgress()
        val ext = if (opt.audioOnly) "m4a" else "mp4"
        val output = File(dir, "out.$ext")
        try {
            mux(parts.map { it.file }, output)
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            // A single audio file is still playable as downloaded.
            if (parts.size == 1) parts[0].file.copyTo(output, overwrite = true)
            else throw DownloadException("Couldn't process the video file.")
        }
        checkCancelled()
        publish(output, ext)
    }

    // ------------------------------------------------------------------ download

    private fun resolveUrl(part: Part, info: org.schabi.newpipe.extractor.stream.StreamInfo) {
        val url = if (part.isVideo) DownloadOptions.findVideo(info, part.itag)?.content
        else DownloadOptions.findAudio(info, part.itag, part.trackId)?.content
        part.url = url ?: throw DownloadException("This quality is no longer available. Pick another one.")
    }

    private fun refreshUrls(parts: List<Part>) {
        if (++refreshes > 3) throw DownloadException("YouTube refused the download. Try again later.")
        YouTubeStreams.invalidate(job.videoId)
        val info = YouTubeStreams.load(job.videoId, fresh = true)
        parts.forEach { resolveUrl(it, info) }
    }

    private fun fetch(part: Part, parts: List<Part>) {
        var failures = 0
        while (true) {
            checkCancelled()
            var pos = part.file.length()
            if (part.size in 0..pos) return

            val end = pos + CHUNK - 1
            val request = Request.Builder()
                .url(part.url)
                .header("User-Agent", YouTubeStreams.userAgentFor(part.url))
                .header("Range", "bytes=$pos-$end")
                .apply { YouTubeStreams.extraHeadersFor(part.url).forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                val call = YouTubeStreams.http.newCall(request)
                job.call = call
                call.execute().use { res ->
                    when (res.code) {
                        206, 200 -> {
                            if (res.code == 200) {
                                // Server ignored the range: restart this file from scratch.
                                if (pos > 0) { part.file.delete(); pos = 0 }
                                res.body?.contentLength()?.takeIf { it > 0 }?.let { part.size = it }
                            } else {
                                res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { part.size = it }
                            }
                            val body = res.body ?: throw IOException("empty body")
                            val got = copy(body.byteStream(), part, parts)
                            failures = 0
                            // Unknown size and a short chunk means the file is complete.
                            if (part.size < 0 && got < CHUNK) { part.size = part.file.length(); return }
                        }
                        416 -> { // asked past the end: done
                            if (pos > 0) { part.size = pos; return }
                            throw DownloadException("The video file is empty.")
                        }
                        403, 410 -> refreshUrls(parts)            // link expired / IP changed
                        429 -> { waitFor(15_000) }               // rate limited
                        in 500..599 -> {
                            if (++failures > MAX_FAILURES) throw DownloadException("YouTube server error. Try again later.")
                            waitFor(backoff(failures))
                        }
                        else -> throw DownloadException("Download failed (HTTP ${res.code}).")
                    }
                }
            } catch (e: IOException) {
                checkCancelled()
                if (isNoSpace(e)) throw DownloadException("Not enough storage space.")
                if (++failures > MAX_FAILURES) throw DownloadException("Network error. Check your connection and try again.")
                waitForNetwork()
                waitFor(backoff(failures))
            } finally {
                job.call = null
            }
        }
    }

    /** Appends the response to the part file; returns bytes written. */
    private fun copy(input: java.io.InputStream, part: Part, parts: List<Part>): Long {
        var written = 0L
        var lastReport = 0L
        val buf = ByteArray(64 * 1024)
        FileOutputStream(part.file, true).use { out ->
            input.use {
                while (true) {
                    if (job.cancelled) throw CancelledException()
                    val n = it.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 400) {
                        lastReport = now
                        report(parts)
                    }
                }
            }
        }
        report(parts)
        return written
    }

    private fun report(parts: List<Part>) {
        job.done = parts.sumOf { it.file.length() }
        job.total = parts.sumOf { if (it.size > 0) it.size else maxOf(it.estimate, it.file.length()) }
        onProgress()
    }

    private fun backoff(failures: Int) = minOf(1000L shl failures.coerceAtMost(5), 30_000L)

    private fun waitFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            checkCancelled()
            try { Thread.sleep(250) } catch (_: InterruptedException) { throw CancelledException() }
        }
    }

    /** Waits (up to 5 min) for any internet connection to come back. */
    private fun waitForNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val until = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < until) {
            val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return
            waitFor(2000)
        }
        throw DownloadException("No internet connection.")
    }

    private fun checkCancelled() {
        if (job.cancelled) throw CancelledException()
    }

    private fun isNoSpace(e: IOException) =
        e.message?.let { it.contains("ENOSPC") || it.contains("No space", ignoreCase = true) } == true

    private fun freeSpace(file: File): Long = try { StatFs(file.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    // ------------------------------------------------------------------ mux

    private fun mux(inputs: List<File>, output: File) {
        output.delete()
        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            class Track(val ex: MediaExtractor, val index: Int, val format: MediaFormat)
            val tracks = inputs.map { file ->
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(file.path)
                val i = (0 until ex.trackCount).first {
                    val mime = ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("video/") || mime.startsWith("audio/")
                }
                ex.selectTrack(i)
                val format = ex.getTrackFormat(i)
                Track(ex, muxer.addTrack(format), format)
            }
            val maxInput = tracks.maxOf { t ->
                if (t.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) t.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }
            val buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            muxer.start()
            started = true

            // Interleave by timestamp so the file streams/plays smoothly.
            val active = tracks.toMutableList()
            var count = 0
            while (active.isNotEmpty()) {
                if (++count % 200 == 0) checkCancelled()
                val t = active.minBy { it.ex.sampleTime.let { s -> if (s < 0) Long.MAX_VALUE else s } }
                val time = t.ex.sampleTime
                if (time < 0) { active.remove(t); continue }
                buffer.clear()
                val size = t.ex.readSampleData(buffer, 0)
                if (size < 0) { active.remove(t); continue }
                val key = t.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(t.index, buffer, info)
                t.ex.advance()
            }
            muxer.stop()
        } finally {
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
            try { muxer.release() } catch (_: Exception) { if (!started) output.delete() }
        }
    }

    // ------------------------------------------------------------------ save

    private fun publish(file: File, ext: String) {
        val audio = job.option.audioOnly
        val mime = if (audio) "audio/mp4" else "video/mp4"
        val clean = job.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        val name = "${clean.ifBlank { job.videoId }} (${job.option.label}).$ext"
        val publicDir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw DownloadException("Couldn't save the file.")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                if (e is IOException && isNoSpace(e)) throw DownloadException("Not enough storage space.")
                throw DownloadException("Couldn't save the file.")
            }
            job.resultUri = uri
            job.location = "$publicDir/$FOLDER"
        } else {
            val canWrite = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val base = if (canWrite) Environment.getExternalStoragePublicDirectory(publicDir)
            else context.getExternalFilesDir(publicDir) ?: context.filesDir
            val folder = File(base, FOLDER).apply { mkdirs() }
            var target = File(folder, name)
            var n = 1
            while (target.exists()) target = File(folder, name.replace(".$ext", " ($n).$ext")).also { n++ }
            try {
                file.copyTo(target)
            } catch (e: IOException) {
                target.delete()
                throw DownloadException(if (isNoSpace(e)) "Not enough storage space." else "Couldn't save the file.")
            }
            job.location = if (canWrite) "$publicDir/$FOLDER" else folder.path
            job.resultUri = Uri.fromFile(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime)) { _, uri ->
                if (uri != null) job.resultUri = uri
            }
        }
    }
}
