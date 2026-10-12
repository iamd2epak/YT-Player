package com.mutetube.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** One entry of the download quality menu. itag -1 = not used. */
data class DownloadOption(
    val label: String,
    val audioOnly: Boolean,
    val videoItag: Int,
    val audioItag: Int,
    val audioTrackId: String?,
    val sizeBytes: Long,
) {
    val menuText: String get() = if (sizeBytes > 0) "$label   ·   ${Downloads.formatSize(sizeBytes)}" else label
}

/** Builds the download menu from a video's streams (MP4/H.264 + M4A, so any phone can mux and play them). */
object DownloadOptions {

    fun from(info: StreamInfo): List<DownloadOption> {
        val duration = info.duration
        val audio = bestAudio(info)
        val byHeight = linkedMapOf<Int, DownloadOption>()

        if (audio != null) {
            info.videoOnlyStreams
                .filter { usable(it) && it.format == MediaFormat.MPEG_4 && (it.codec?.startsWith("avc") ?: true) }
                .groupBy { side(it) }
                .filterKeys { it > 0 }
                .forEach { (h, list) ->
                    val v = list.maxBy { it.bitrate }
                    byHeight[h] = DownloadOption("${h}p", false, v.itag, audio.itag, audio.audioTrackId,
                        size(v, duration) + size(audio, duration))
                }
        }
        // Ready-made video+audio files (usually 360p) for resolutions not covered above.
        info.videoStreams.filter { usable(it) && it.format == MediaFormat.MPEG_4 && side(it) > 0 }
            .forEach { v ->
                val h = side(v)
                if (h !in byHeight) byHeight[h] = DownloadOption("${h}p", false, v.itag, -1, null, size(v, duration))
            }

        val list = byHeight.entries.sortedByDescending { it.key }.map { it.value }.toMutableList()
        if (audio != null) list += DownloadOption("Audio only", true, -1, audio.itag, audio.audioTrackId, size(audio, duration))
        return list
    }

    fun findVideo(info: StreamInfo, itag: Int): VideoStream? =
        (info.videoOnlyStreams + info.videoStreams).firstOrNull { usable(it) && it.itag == itag }

    fun findAudio(info: StreamInfo, itag: Int, trackId: String?): AudioStream? {
        val same = info.audioStreams.filter { usable(it) && it.itag == itag }
        return same.firstOrNull { it.audioTrackId == trackId } ?: same.firstOrNull()
    }

    /** Original-language (not auto-dubbed) M4A audio, highest bitrate. */
    private fun bestAudio(info: StreamInfo): AudioStream? {
        val m4a = info.audioStreams.filter { usable(it) && it.format == MediaFormat.M4A }
        val original = m4a.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { m4a }
        return original.maxByOrNull { it.averageBitrate }
    }

    private fun usable(s: Stream) = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl

    private fun side(v: VideoStream): Int {
        if (v.width > 0 && v.height > 0) return minOf(v.width, v.height)
        return v.resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun size(s: Stream, durationSec: Long): Long {
        val exact = s.itagItem?.contentLength ?: -1
        if (exact > 0) return exact
        val bps = when (s) {
            is VideoStream -> s.bitrate
            is AudioStream -> s.bitrate
            else -> 0
        }
        return if (bps > 0 && durationSec > 0) bps.toLong() * durationSec / 8 else 0
    }
}

/** In-process download queue + state shared by the service and the UI. */
object Downloads {

    enum class Status { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELLED }

    class Job(val id: Int, val videoId: String, val title: String, val option: DownloadOption) {
        @Volatile var status = Status.QUEUED
        @Volatile var done = 0L
        @Volatile var total = option.sizeBytes
        @Volatile var error = ""
        @Volatile var location = ""
        @Volatile var resultUri: Uri? = null
        @Volatile var cancelled = false
        @Volatile var call: Call? = null

        val active get() = status == Status.QUEUED || status == Status.RUNNING || status == Status.SAVING
        val percent get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    fun interface Listener { fun onChanged(job: Job) }

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(1)
    private val jobs = CopyOnWriteArrayList<Job>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Returns null if the same video/quality is already downloading. */
    fun create(videoId: String, title: String, option: DownloadOption): Job? {
        if (jobs.any { it.active && it.videoId == videoId && it.option.label == option.label }) return null
        // Forget old finished jobs so the list never grows.
        jobs.filter { !it.active }.dropLast(10).forEach { jobs.remove(it) }
        return Job(ids.getAndIncrement(), videoId, title, option).also { jobs += it; changed(it) }
    }

    fun get(id: Int) = jobs.firstOrNull { it.id == id }
    fun latestFor(videoId: String) = jobs.lastOrNull { it.videoId == videoId }
    fun nextQueued() = jobs.firstOrNull { it.status == Status.QUEUED && !it.cancelled }
    fun queuedCount() = jobs.count { it.status == Status.QUEUED && !it.cancelled }
    fun hasActive() = jobs.any { it.active }

    fun cancel(id: Int) {
        val job = get(id) ?: return
        job.cancelled = true
        job.call?.cancel()
        if (job.status == Status.QUEUED) {
            job.status = Status.CANCELLED
            changed(job)
        }
    }

    fun cancelAll() = jobs.filter { it.active }.forEach { cancel(it.id) }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun changed(job: Job) = main.post { listeners.forEach { it.onChanged(job) } }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
