package com.iamd2epak.ytplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Opens videos coming from other apps: shared links, "Open with" video files, shared files. */
class LinkActivity : Activity() {

    companion object {
        /** Intent that plays [text]: an online video link or any direct http(s) video link. */
        fun playerIntentForText(context: Context, text: String): Intent? {
            YouTubeId.parse(text)?.let {
                return Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_VIDEO, it)
            }
            val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text)?.value ?: return null
            return Intent(context, PlayerActivity::class.java).setData(Uri.parse(url))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = try { resolve() } catch (_: Exception) { null } // malformed intents from other apps
        if (target == null) {
            Toast.makeText(this, "Can't open this", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (_: Exception) {
                Toast.makeText(this, "Can't open this", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun resolve(): Intent? {
        val i = intent ?: return null
        return when (i.action) {
            Intent.ACTION_VIEW -> {
                val data = i.data ?: return null
                if (data.scheme == "content" || data.scheme == "file") fileIntent(data, i.type)
                else playerIntentForText(this, data.toString())
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                if (stream != null) fileIntent(stream, i.type)
                else playerIntentForText(this, i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
            }
            else -> null
        }
    }

    /** Forwards a file with its temporary read permission. Never opens this app's own private files. */
    private fun fileIntent(uri: Uri, type: String?): Intent? {
        if (uri.scheme == "file") {
            val path = try { java.io.File(uri.path ?: return null).canonicalPath } catch (_: Exception) { return null }
            if (path.startsWith(applicationInfo.dataDir) || path.startsWith("/data/")) return null
        }
        return fileIntentUnchecked(uri, type)
    }

    private fun fileIntentUnchecked(uri: Uri, type: String?) = Intent(this, PlayerActivity::class.java)
        .setDataAndType(uri, type ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
