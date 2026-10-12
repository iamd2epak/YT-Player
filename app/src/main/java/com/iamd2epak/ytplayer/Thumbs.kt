package com.iamd2epak.ytplayer

import android.graphics.Bitmap
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors

/** Small thumbnail loader: memory + disk cache, RGB_565 to save RAM. */
object Thumbs {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** [uri] = normal video (local file): a frame is taken from the file itself. */
    fun load(view: ImageView, videoId: String, uri: String = "") {
        view.tag = videoId
        memory.get(videoId)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val context = view.context.applicationContext
        val dir = File(context.cacheDir, "thumbs")
        pool.execute {
            val bitmap = try {
                when {
                    uri.isBlank() -> fetch(dir, videoId)
                    uri.startsWith("content:") || uri.startsWith("file:") -> frame(context, dir, videoId, Uri.parse(uri))
                    else -> null // direct web links: no thumbnail (would need to download the video)
                }
            } catch (_: Throwable) { null } ?: return@execute
            memory.put(videoId, bitmap)
            main.post { if (view.tag == videoId) view.setImageBitmap(bitmap) }
        }
    }

    private fun frame(context: Context, dir: File, key: String, uri: Uri): Bitmap? {
        val file = File(dir, "$key.jpg")
        if (!file.exists()) {
            val mmr = MediaMetadataRetriever()
            val frame = try {
                mmr.setDataSource(context, uri)
                mmr.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            } ?: return null
            val w = 320
            val h = (frame.height * w / frame.width.coerceAtLeast(1)).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(frame, w, h, true)
            if (small !== frame) frame.recycle()
            dir.mkdirs()
            file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            return small
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    private fun fetch(dir: File, videoId: String): Bitmap? {
        val file = File(dir, "$videoId.jpg")
        if (!file.exists()) {
            dir.mkdirs()
            val req = Request.Builder().url(YouTubeId.thumbnail(videoId)).build()
            YouTubeStreams.http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val tmp = File(dir, "$videoId.tmp")
                tmp.outputStream().use { out -> res.body?.byteStream()?.copyTo(out) ?: return null }
                tmp.renameTo(file)
            }
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(file.path, opts) ?: run { file.delete(); null }
    }
}
