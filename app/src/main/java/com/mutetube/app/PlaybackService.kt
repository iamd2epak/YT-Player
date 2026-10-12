package com.mutetube.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background play: exposes the player screen's ExoPlayer through a MediaSession, so playback
 * continues (with a media notification + lock-screen controls) after leaving the app.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile var player: Player? = null
        @Volatile var videoId = ""
        @Volatile var title = ""
        @Volatile var channel = ""
        @Volatile var directUri: Uri? = null
        private var instance: PlaybackService? = null

        fun start(context: Context, player: Player, videoId: String, title: String, channel: String) {
            this.player = player
            this.videoId = videoId
            this.title = title
            this.channel = channel
            instance?.let { it.releaseSession(); it.createSession() }
            context.startService(Intent(context, PlaybackService::class.java))
        }

        /** Stops background play for [player] (no-op if another player owns it). */
        fun stop(context: Context, player: Player?) {
            if (player != null && this.player !== player) return
            instance?.releaseSession()
            this.player = null
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createSession()
    }

    private fun createSession() {
        val p = player ?: return stopSelf()
        val wrapped = object : ForwardingPlayer(p) {
            override fun getMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
                .setTitle(title.ifBlank { getString(R.string.app_name) })
                .setArtist(channel.ifBlank { getString(R.string.app_name) })
                .apply {
                    // Online videos have a thumbnail; local files don't.
                    if (YouTubeId.parse(videoId) == videoId) setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                }
                .build()
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO, videoId)
                .apply { directUri?.let { setData(it) } }
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            session = MediaSession.Builder(this, wrapped)
                .setId("bg-$videoId-${System.nanoTime()}")
                .setSessionActivity(open)
                .build()
                .also { addSession(it) }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App swiped away from recents: stop playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        stopSelf()
    }

    fun releaseSession() {
        session?.let {
            try { removeSession(it) } catch (_: Exception) {}
            it.release()
        }
        session = null
    }

    override fun onDestroy() {
        releaseSession()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
