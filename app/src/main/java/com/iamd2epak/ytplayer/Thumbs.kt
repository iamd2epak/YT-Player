package com.iamd2epak.ytplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    fun load(view: ImageView, videoId: String) {
        view.tag = videoId
        memory.get(videoId)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val dir = File(view.context.cacheDir, "thumbs")
        pool.execute {
            val bitmap = try { fetch(dir, videoId) } catch (_: Throwable) { null } ?: return@execute
            memory.put(videoId, bitmap)
            main.post { if (view.tag == videoId) view.setImageBitmap(bitmap) }
        }
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
