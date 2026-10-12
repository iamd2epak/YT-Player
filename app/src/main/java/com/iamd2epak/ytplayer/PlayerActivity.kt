package com.iamd2epak.ytplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Course lecture player.
 *
 * Layers (bottom → top):
 *   1. PlayerView  – ExoPlayer playing the video's raw streams (no YouTube UI at all).
 *   2. WebView     – transparent page with our controls + gestures (player.html).
 *
 * If streaming is impossible, the same page switches to the YouTube embed as a fallback.
 * Navigation/popups out of the page are blocked; no long-press, no text selection.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_TITLE = "title"

        // Set to true before publishing to block screenshots / screen recording.
        private const val BLOCK_SCREENSHOTS = false

        // The page is served by the app itself from this https URL (intercepted, never fetched).
        // A real https origin is needed for the YouTube-embed fallback to work.
        private const val PAGE_URL = "https://iamd2epak.github.io/yt-player/player.html"
        private const val PREFS = "progress"
    }

    private lateinit var web: WebView
    private lateinit var playerView: PlayerView
    private lateinit var videoId: String
    private var stream: StreamPlayer? = null
    private var title = ""
    private var mode = "native"
    private var fullscreen = false
    private var pageReady = false
    @Volatile private var pageHtml = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BLOCK_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = YouTubeId.parse(intent.getStringExtra(EXTRA_VIDEO).orEmpty())
        if (id == null) {
            Toast.makeText(this, "Invalid video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoId = id
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
        }

        WebView.setWebContentsDebuggingEnabled(false)
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)          // routed to onCreateWindow -> rejected
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            saveFormData = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() != PAGE_URL) return null
                return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(pageHtml.toByteArray(Charsets.UTF_8)))
                    .apply {
                        setStatusCodeAndReasonPhrase(200, "OK")
                        responseHeaders = mapOf("Cache-Control" to "no-store")
                    }
            }

            // Block every top-level navigation (logo, title, end-screen links...).
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame

            override fun onPageFinished(view: WebView, url: String) {
                if (url != PAGE_URL || pageReady) return
                pageReady = true
                if (mode == "native") startStream()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        web.addJavascriptInterface(Bridge(), "Android")

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(playerView, match)
        root.addView(web, match)
        setContentView(root)
        applySystemUi()

        loadPage("native")
    }

    private fun savedPosition(): Double = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(videoId, 0f).toDouble()

    private fun loadPage(newMode: String, startAt: Double = savedPosition()) {
        mode = newMode
        pageReady = false
        val html = assets.open("player.html").bufferedReader().use { it.readText() }
            .replace("{{VIDEO_ID}}", videoId)
            .replace("{{START_AT}}", startAt.toInt().toString())
            .replace("{{TITLE}}", strictEncode(title))
            .replace("{{MODE}}", newMode)
        pageHtml = html
        web.loadUrl(PAGE_URL)
    }

    private fun startStream() {
        stream?.release()
        stream = StreamPlayer(this, playerView, videoId, object : StreamPlayer.Listener {
            override fun onStatus(text: String) = js("window.nativeStatus&&nativeStatus(${JSONObject.quote(text)})")
            override fun onState(json: String) = js("window.nativeState&&nativeState($json)")
            override fun onError(message: String) = js("window.nativeError&&nativeError(${JSONObject.quote(message)})")
            override fun onSaveProgress(positionSec: Double, durationSec: Double) = saveProgress(positionSec, durationSec)
            override fun onFallbackToEmbed(positionSec: Double) = switchToEmbed(positionSec)
        }).also { it.start(savedPosition()) }
    }

    /** Last resort: YouTube's own player inside the same page/controls. */
    private fun switchToEmbed(positionSec: Double) {
        stream?.release()
        stream = null
        playerView.visibility = View.GONE
        web.setBackgroundColor(Color.BLACK)
        loadPage("embed", positionSec)
    }

    private fun js(code: String) {
        if (pageReady && !isFinishing) web.evaluateJavascript(code, null)
    }

    private fun saveProgress(current: Double, duration: Double) {
        // Reset near the end so a finished lecture starts from the beginning next time.
        val pos = if (duration > 0 && current > duration - 15) 0f else current.toFloat()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(videoId, pos).apply()
    }

    /** Percent-encode everything except [A-Za-z0-9] so the title is safe inside a JS string. */
    private fun strictEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code) append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }

    inner class Bridge {
        /** Player commands from the page (native mode). */
        @JavascriptInterface
        fun cmd(name: String, arg: String) {
            runOnUiThread {
                val s = stream ?: return@runOnUiThread
                when (name) {
                    "play" -> s.play()
                    "pause" -> s.pause()
                    "seek" -> arg.toDoubleOrNull()?.let { s.seekTo(it) }
                    "rate" -> arg.toFloatOrNull()?.let { s.setSpeed(it) }
                    "quality" -> s.setQuality(arg)
                }
            }
        }

        /** Progress from the YouTube-embed fallback. */
        @JavascriptInterface
        fun onProgress(current: Double, duration: Double) = saveProgress(current, duration)

        @JavascriptInterface
        fun reload() {
            runOnUiThread {
                if (mode == "native" && stream != null) stream?.retry() else loadPage(mode)
            }
        }

        @JavascriptInterface
        fun toggleFullscreen() {
            runOnUiThread { setFullscreen(!fullscreen) }
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        js("window.setFullscreenIcon&&setFullscreenIcon($on)")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fullscreen = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        js("window.setFullscreenIcon&&setFullscreenIcon($fullscreen)")
        applySystemUi()
    }

    @Suppress("DEPRECATION")
    private fun applySystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) {
            stream?.pause()
            js("window.pauseVideo&&pauseVideo()")
            web.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        stream?.release()
        stream = null
        if (::web.isInitialized) {
            web.removeJavascriptInterface("Android")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
