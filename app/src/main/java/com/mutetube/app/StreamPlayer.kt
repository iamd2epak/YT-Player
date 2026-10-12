package com.mutetube.app

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.Executors

/**
 * Plays a YouTube video from its raw streams with ExoPlayer.
 *
 * Playback plans, tried in order until one works:
 *   1. HLS        – adaptive, seamless quality switching (best)
 *   2. Separate   – video-only + audio progressive files, quality = choose file
 *   3. Combined   – single 360p file with audio
 * If all fail (or extraction is blocked) the activity falls back to the embedded web player.
 *
 * With [directUri] it plays a normal video instead: a local file (content:// / file://) or a
 * direct http(s) link (MP4, MKV, WebM, HLS, DASH...). No extraction, no fallback.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val videoId: String,
    private val listener: Listener,
    private val directUri: Uri? = null,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onState(json: String)
        fun onError(message: String)
        fun onFallbackToEmbed(positionSec: Double)
        fun onSaveProgress(positionSec: Double, durationSec: Double)
        fun onInfo(info: StreamInfo) {}
    }

    private enum class Plan(val label: String) { HLS("HLS"), SEPARATE("Direct"), COMBINED("Basic"), FILE("File") }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** ~4 GB RAM or less (or Android "low RAM"): keep Auto at 720p and use smaller buffers. */
    private val lowEnd: Boolean = run {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        am?.isLowRamDevice == true || mem.totalMem in 1..4_500_000_000L
    }
    private val autoMax = if (lowEnd) 720 else 1080

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)          // never show captions
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)       // hardware decoding on every phone
            .setMaxVideoSize(Int.MAX_VALUE, autoMax)                // Auto never picks heavy 1440p/4K
            .build()
    }
    private val dataSource = YouTubeDataSource.Factory()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, if (lowEnd) 30_000 else 50_000, 1_000, 2_000)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK) // keeps playing with the screen off (background play)
        .build()

    private var info: StreamInfo? = null
    val currentInfo: StreamInfo? get() = info
    private var plans = listOf<Plan>()
    private var planIndex = 0
    private var refreshedThisPlan = false
    private var released = false
    private var loading = false
    private var waitingForNetwork = false

    // Quality state
    private var selectedQuality = "auto"                 // "auto" or short-side pixels, e.g. "720"
    private var separateVideos = listOf<VideoStream>()   // one per resolution, best first
    private var separateAudio: AudioStream? = null
    private var separateCurrent: VideoStream? = null
    private var originalAudioApplied = false

    // Auto quality for the "Separate" plan: step down after repeated stalls.
    private val stallTimes = ArrayDeque<Long>()
    private var lastSeekAt = 0L

    private var lastSavedAt = 0L

    init {
        view.player = player
        view.useController = false
        view.keepScreenOn = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) onStall()
                if (state == Player.STATE_READY) {
                    refreshedThisPlan = false
                    waitingForNetwork = false
                }
                if (state == Player.STATE_ENDED) save(force = true)
                pushState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) save(force = true)
                pushState()
            }

            override fun onTracksChanged(tracks: Tracks) {
                preferOriginalAudio(tracks)
                pushState()
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
    }

    // ------------------------------------------------------------------ loading

    fun start(startSec: Double) {
        if (directUri != null) {
            plans = listOf(Plan.FILE)
            planIndex = 0
            playCurrentPlan(startSec)
        } else load(startSec, fresh = false)
        main.post(ticker)
        registerNetworkCallback()
    }

    /** Retry button: refetch everything and continue where we were. */
    fun retry() {
        val pos = positionSec()
        if (directUri != null) {
            playCurrentPlan(pos)
            return
        }
        info = null
        planIndex = 0
        load(pos, fresh = true)
    }

    private fun load(startSec: Double, fresh: Boolean) {
        if (released || loading) return
        loading = true
        listener.onStatus(if (fresh) "Reconnecting..." else "Loading video...")
        worker.execute {
            val result = runCatching { YouTubeStreams.load(videoId, fresh) }
            main.post {
                loading = false
                if (released) return@post
                result.onSuccess { onInfo(it, startSec) }
                result.onFailure { e ->
                    val se = e as? YouTubeStreams.StreamException
                    val message = se?.message ?: "Couldn't load this video."
                    if (se != null && !se.retryable) {
                        listener.onError(message)                  // private / removed / blocked
                    } else if (message.startsWith("No internet")) {
                        waitingForNetwork = true
                        listener.onError(message)                  // auto-retries when online
                    } else {
                        listener.onFallbackToEmbed(startSec)       // extractor blocked/outdated
                    }
                }
            }
        }
    }

    private fun onInfo(newInfo: StreamInfo, startSec: Double) {
        info = newInfo
        listener.onInfo(newInfo)
        separateVideos = pickVideoStreams(newInfo.videoOnlyStreams)
        separateAudio = pickAudioStream(newInfo.audioStreams)
        plans = buildList {
            if (newInfo.hlsUrl.isNotEmpty()) add(Plan.HLS)
            if (separateVideos.isNotEmpty() && separateAudio != null) add(Plan.SEPARATE)
            if (newInfo.videoStreams.any { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }) add(Plan.COMBINED)
        }
        if (planIndex >= plans.size) planIndex = 0
        if (plans.isEmpty()) {
            listener.onFallbackToEmbed(startSec)
            return
        }
        playCurrentPlan(startSec)
    }

    private fun playCurrentPlan(startSec: Double) {
        val plan = plans[planIndex]
        val source: MediaSource = if (plan == Plan.FILE) {
            val http = OkHttpDataSource.Factory(YouTubeStreams.http)
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                .createMediaSource(MediaItem.fromUri(directUri!!))
        } else when (plan) {
            Plan.HLS -> HlsMediaSource.Factory(dataSource)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(info!!.hlsUrl))

            Plan.SEPARATE -> {
                val video = chooseSeparateVideo()
                separateCurrent = video
                MergingMediaSource(
                    true, true,
                    progressive(video.content),
                    progressive(separateAudio!!.content),
                )
            }

            Plan.FILE -> return
            Plan.COMBINED -> progressive(
                info!!.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxBy { it.height }.content
            )
        }
        originalAudioApplied = false
        player.setMediaSource(source, (startSec * 1000).toLong().coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        pushState()
    }

    private fun progressive(url: String): MediaSource =
        ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url))

    // ------------------------------------------------------------------ errors

    private fun handleError(error: PlaybackException) {
        if (released) return
        val pos = positionSec()
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

        if (directUri != null) return handleFileError(error, http)

        when {
            // Offline / flaky network: keep the plan, wait and resume.
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                waitingForNetwork = true
                listener.onStatus("No internet. Waiting for connection...")
                main.postDelayed({ if (waitingForNetwork && !released) player.prepare() }, 5000)
            }

            // Link expired / refused: get fresh links once, then try the next plan.
            http != null || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                if (!refreshedThisPlan) {
                    refreshedThisPlan = true
                    YouTubeStreams.invalidate(videoId)
                    load(pos, fresh = true)
                } else nextPlan(pos)
            }

            // Decoder / format / anything else: try the next plan.
            else -> nextPlan(pos)
        }
    }

    /** Normal videos: wait for the network, otherwise explain what's wrong. */
    private fun handleFileError(error: PlaybackException, http: Int?) {
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) {
            waitingForNetwork = true
            listener.onStatus("No internet. Waiting for connection...")
            main.postDelayed({ if (waitingForNetwork && !released) player.prepare() }, 5000)
            return
        }
        listener.onError(when {
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This video file was moved or deleted."
            code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to open this file. Open it again from your files."
            http != null -> "Couldn't open this link (error $http)."
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Couldn't open this link."
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This file isn't a playable video."
            code in 4000..4999 -> "This video format isn't supported on your phone."
            else -> "Can't play this video."
        })
    }

    private fun nextPlan(pos: Double) {
        refreshedThisPlan = false
        planIndex++
        if (planIndex < plans.size) {
            listener.onStatus("Switching stream...")
            playCurrentPlan(pos)
        } else {
            planIndex = 0
            // Posted: we're inside an ExoPlayer callback and the fallback releases the player.
            main.post { if (!released) listener.onFallbackToEmbed(pos) }
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (released || !waitingForNetwork) return@post
                waitingForNetwork = false
                if (info == null && directUri == null) load(positionSec(), fresh = true) else player.prepare()
            }
        }
    }

    // ------------------------------------------------------------------ quality

    /** One stream per resolution: prefer H.264/MP4 (hardware decoding everywhere), then VP9. */
    private fun pickVideoStreams(all: List<VideoStream>): List<VideoStream> =
        all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && shortSide(it) > 0 }
            .groupBy { shortSide(it) }
            .map { (_, list) -> list.sortedWith(compareBy({ it.format != MediaFormat.MPEG_4 }, { -it.bitrate })).first() }
            .sortedByDescending { shortSide(it) }

    /** Original-language audio (not an auto-dub), preferring AAC/M4A, highest bitrate. */
    private fun pickAudioStream(all: List<AudioStream>): AudioStream? {
        val usable = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { usable }
        return original.sortedWith(compareBy({ it.format != MediaFormat.M4A }, { -it.averageBitrate })).firstOrNull()
    }

    private fun shortSide(v: VideoStream): Int {
        if (v.width > 0 && v.height > 0) return minOf(v.width, v.height)
        return v.resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun chooseSeparateVideo(): VideoStream {
        selectedQuality.toIntOrNull()?.let { want ->
            separateVideos.firstOrNull { shortSide(it) == want }?.let { return it }
        }
        // Auto: fit the screen (max 1080p), stepping down if we stalled a lot.
        val screen = minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val cap = minOf(screen, autoCap)
        return separateVideos.firstOrNull { shortSide(it) <= cap } ?: separateVideos.last()
    }

    private var autoCap = autoMax

    private fun onStall() {
        if (plans.getOrNull(planIndex) != Plan.SEPARATE || selectedQuality != "auto") return
        val now = System.currentTimeMillis()
        if (now - lastSeekAt < 3000) return // buffering after a seek is expected
        stallTimes.addLast(now)
        while (stallTimes.isNotEmpty() && now - stallTimes.first() > 60_000) stallTimes.removeFirst()
        val current = separateCurrent ?: return
        if (stallTimes.size >= 3) {
            val lower = separateVideos.firstOrNull { shortSide(it) < shortSide(current) } ?: return
            stallTimes.clear()
            autoCap = shortSide(lower)
            playCurrentPlan(positionSec())
        }
    }

    fun setQuality(id: String) {
        selectedQuality = id
        when (plans.getOrNull(planIndex)) {
            Plan.HLS, Plan.FILE -> applyHlsQuality()
            Plan.SEPARATE -> {
                if (id == "auto") autoCap = autoMax
                val wasPlaying = player.playWhenReady
                playCurrentPlan(positionSec())
                player.playWhenReady = wasPlaying
            }
            else -> {}
        }
        pushState()
    }

    private fun applyHlsQuality() {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        val want = selectedQuality.toIntOrNull()
        if (want != null) {
            hlsVideoTracks().firstOrNull { it.third == want }?.let { (group, index, _) ->
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    /** (group, trackIndex, shortSide) — one per resolution, H.264 preferred. */
    private fun hlsVideoTracks(): List<Triple<Tracks.Group, Int, Int>> {
        val all = mutableListOf<Triple<Tracks.Group, Int, Format>>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) all += Triple(group, i, group.getTrackFormat(i))
            }
        }
        return all.filter { shortSide(it.third) > 0 }
            .groupBy { shortSide(it.third) }
            .map { (side, list) ->
                val best = list.sortedWith(compareBy({ it.third.sampleMimeType != MimeTypes.VIDEO_H264 }, { -it.third.bitrate })).first()
                Triple(best.first, best.second, side)
            }
            .sortedByDescending { it.third }
    }

    private fun shortSide(f: Format): Int = if (f.width > 0 && f.height > 0) minOf(f.width, f.height) else 0

    /** HLS carries auto-dubbed audio tracks too: make sure the original one plays. */
    private fun preferOriginalAudio(tracks: Tracks) {
        if (originalAudioApplied) return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val label = group.getTrackFormat(i).label ?: continue
                if (label.contains("original", ignoreCase = true) && group.isTrackSupported(i)) {
                    originalAudioApplied = true
                    if (!group.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                    }
                    return
                }
            }
        }
    }

    private fun qualityOptions(): List<Pair<String, String>> {
        val sides = when (plans.getOrNull(planIndex)) {
            Plan.HLS, Plan.FILE -> hlsVideoTracks().map { it.third }
            Plan.SEPARATE -> separateVideos.map { shortSide(it) }
            else -> emptyList()
        }
        val maxOffered = if (lowEnd) 1080 else 2160 // 1440p/4K would stutter on low-end phones
        val offered = sides.filter { it <= maxOffered }
        if (offered.isEmpty() || (directUri != null && offered.size < 2)) return emptyList()
        return listOf("auto" to "Auto") + offered.map { it.toString() to "${it}p" }
    }

    // ------------------------------------------------------------------ controls

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun pause() = player.pause()

    /** Background play: stop decoding/downloading video while nothing is on screen. */
    fun setVideoEnabled(enabled: Boolean) {
        if (released) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    fun seekTo(sec: Double) {
        lastSeekAt = System.currentTimeMillis()
        player.seekTo((sec * 1000).toLong().coerceAtLeast(0))
        pushState()
    }

    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))

    fun positionSec(): Double = player.currentPosition / 1000.0

    private fun durationSec(): Double {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0) return d / 1000.0
        return info?.duration?.toDouble() ?: 0.0
    }

    // ------------------------------------------------------------------ state → page

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            pushState()
            save(force = false)
            main.postDelayed(this, 500) // page interpolates between updates
        }
    }

    private fun pushState() {
        if (released || (info == null && directUri == null)) return
        val state = when {
            player.playbackState == Player.STATE_ENDED -> "ended"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.playbackState == Player.STATE_IDLE -> if (player.playerError != null) "paused" else "buffering"
            player.isPlaying -> "playing"
            player.playWhenReady -> "buffering"
            else -> "paused"
        }
        val d = durationSec()
        val playingSide = player.videoFormat?.let { shortSide(it) } ?: separateCurrent?.let { shortSide(it) } ?: 0
        val json = JSONObject()
            .put("state", state)
            .put("t", positionSec())
            .put("d", d)
            .put("buf", if (d > 0) (player.bufferedPosition / 1000.0 / d).coerceIn(0.0, 1.0) else 0.0)
            .put("quality", selectedQuality)
            .put("label", if (playingSide > 0) "${playingSide}p" else "")
            .put("engine", plans.getOrNull(planIndex)?.label ?: "")
            .put("qualities", JSONArray().apply {
                qualityOptions().forEach { (id, label) -> put(JSONObject().put("id", id).put("label", label)) }
            })
        listener.onState(json.toString())
    }

    private fun save(force: Boolean) {
        val now = System.currentTimeMillis()
        if ((info == null && directUri == null) || (!force && now - lastSavedAt < 5000)) return
        lastSavedAt = now
        listener.onSaveProgress(positionSec(), durationSec())
    }

    fun release() {
        if (released) return
        save(force = true)
        released = true
        main.removeCallbacksAndMessages(null)
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        worker.shutdownNow()
        view.player = null
        player.release()
    }
}
