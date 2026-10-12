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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Locked-down YouTube player:
 *  - YouTube iframe gets zero touches (HTML shield on top + pointer-events:none),
 *    so logo / title / "Copy link" / long-press menus can never be reached.
 *  - Every navigation or popup out of the player page is blocked.
 *  - No text selection, no context menu, no long press, no WebView debugging.
 *  - FLAG_SECURE blocks screenshots and screen recording.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_TITLE = "title"

        // Page origin sent to YouTube as Referer/origin. Any real https origin works;
        // put your own website domain here if you have one.
        private const val BASE_URL = "https://iamd2epak.github.io/"
        private const val PREFS = "progress"
    }

    private lateinit var web: WebView
    private lateinit var videoId: String
    private var title = ""
    private var fullscreen = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = YouTubeId.parse(intent.getStringExtra(EXTRA_VIDEO).orEmpty())
        if (id == null) {
            Toast.makeText(this, "Invalid video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoId = id
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        WebView.setWebContentsDebuggingEnabled(false)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
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
            mediaPlaybackRequiresUserGesture = false // video starts instantly
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
            // Block every top-level navigation (YouTube logo, title, end-screen, links...).
            // Nothing can take the user out of the player page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame
        }
        web.webChromeClient = object : WebChromeClient() {
            // Block target=_blank / window.open popups.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        web.addJavascriptInterface(Bridge(), "Android")

        setContentView(web)
        applySystemUi()

        loadPlayer()
    }

    private fun loadPlayer() {
        val startAt = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(videoId, 0f)
        val html = assets.open("player.html").bufferedReader().use { it.readText() }
            .replace("{{VIDEO_ID}}", videoId)
            .replace("{{START_AT}}", startAt.toInt().toString())
            .replace("{{TITLE}}", strictEncode(title))
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
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
        @JavascriptInterface
        fun onProgress(current: Double, duration: Double) {
            // Store resume point; reset near the end so the lecture restarts next time.
            val pos = if (duration > 0 && current > duration - 15) 0f else current.toFloat()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(videoId, pos).apply()
        }

        @JavascriptInterface
        fun reload() {
            runOnUiThread { loadPlayer() }
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
        web.evaluateJavascript("window.setFullscreenIcon&&setFullscreenIcon($on)", null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fullscreen = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (::web.isInitialized) web.evaluateJavascript("window.setFullscreenIcon&&setFullscreenIcon($fullscreen)", null)
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
            web.evaluateJavascript("window.pauseVideo&&pauseVideo()", null)
            web.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("Android")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
