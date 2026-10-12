package com.iamd2epak.ytplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives links shared from YouTube (or any app) and opens them in the player. */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = try {
            when (intent?.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            }
        } catch (_: Exception) {
            null // malformed extras from another app
        }.orEmpty()

        val id = YouTubeId.parse(text)
        if (id == null) {
            Toast.makeText(this, "Not a YouTube link", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
