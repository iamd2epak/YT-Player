package com.iamd2epak.ytplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.OpenableColumns
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Video player screen.
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
        private const val RC_STORAGE = 11
        private const val RC_NOTIFY = 12
        private const val BG = "#0F0F0F"
        private const val CARD = "#212121"
        private const val ACCENT = "#E53935"
        private var askedNotify = false
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
    private var pendingMessage: String? = null   // status/error raised before the page finished loading
    private lateinit var root: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var details: ScrollView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var downloadBtn: LinearLayout
    private lateinit var bgBtn: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardText: TextView
    private lateinit var cardClose: ImageView
    private lateinit var cardProgress: ProgressBar
    private var channel = ""
    private var directUri: Uri? = null   // normal video: local file or direct link
    private var bgOn = false
    private var videoOff = false
    private var metaFallbackStarted = false
    private var pendingDownload: DownloadOption? = null
    private var cardJobId = -1
    private val downloadListener = Downloads.Listener { job -> if (job.videoId == videoId) renderCard(job) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BLOCK_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        directUri = intent.data?.takeIf { it.scheme in setOf("content", "file", "http", "https") }
        val id = YouTubeId.parse(intent.getStringExtra(EXTRA_VIDEO).orEmpty())
            ?: directUri?.let { History.keyFor(it.toString()) }
        if (id == null) {
            Toast.makeText(this, "Invalid video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoId = id
        if (YouTubeId.parse(intent.getStringExtra(EXTRA_VIDEO).orEmpty()) != null) directUri = null
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { History.title(this, id) }
            .ifBlank { directUri?.let { displayName(it) }.orEmpty() }
        History.put(this, id, title, uri = directUri?.toString().orEmpty())

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
                js("window.setFullscreenIcon&&setFullscreenIcon($fullscreen)")
                pendingMessage?.let { js(it) }
                pendingMessage = null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        web.addJavascriptInterface(Bridge(), "Android")

        // Portrait: toolbar, 16:9 player, then title + actions.
        // Landscape / fullscreen: only the player.
        val match = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        playerBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, match)
            addView(web, match)
        }
        toolbar = buildToolbar()
        details = buildDetails()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(playerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Keep the portrait player below the status bar; landscape uses the whole screen.
            if (isLandscape()) v.setPadding(0, 0, 0, 0)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        applyLayout()
        directUri?.let { uri ->
            // Normal video: no download, show where it comes from.
            downloadBtn.visibility = View.GONE
            renderMeta(if (uri.scheme == "http" || uri.scheme == "https") uri.host.orEmpty() else "On this phone")
        }
        Downloads.addListener(downloadListener)
        Downloads.latestFor(videoId)?.takeIf { it.active }?.let { renderCard(it) }

        loadPage("native")
        startStream() // fetch streams while the page loads: faster start
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayout() {
        val landscape = isLandscape()
        fullscreen = landscape
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (landscape) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
            toolbar.visibility = View.GONE
            details.visibility = View.GONE
            root.setBackgroundColor(Color.BLACK)
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
            toolbar.visibility = View.VISIBLE
            details.visibility = View.VISIBLE
            root.setBackgroundColor(Color.parseColor(BG))
        }
        playerBox.layoutParams = lp
        applySystemUi()
        root.requestApplyInsets()
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
            override fun onStatus(text: String) = jsOrQueue("window.nativeStatus&&nativeStatus(${JSONObject.quote(text)})")
            override fun onState(json: String) = js("window.nativeState&&nativeState($json)")
            override fun onError(message: String) {
                jsOrQueue("window.nativeError&&nativeError(${JSONObject.quote(message)})")
                fetchMetaFallback()
            }
            override fun onInfo(info: StreamInfo) = showInfo(info)
            override fun onSaveProgress(positionSec: Double, durationSec: Double) = saveProgress(positionSec, durationSec)
            override fun onFallbackToEmbed(positionSec: Double) = switchToEmbed(positionSec)
        }, directUri).also { it.start(savedPosition()) }
    }

    /** Last resort: YouTube's own player inside the same page/controls. */
    private fun switchToEmbed(positionSec: Double) {
        if (bgOn) PlaybackService.stop(this, stream?.player)
        bgOn = false
        renderBgButton()
        stream?.release()
        stream = null
        playerView.visibility = View.GONE
        web.setBackgroundColor(Color.BLACK)
        loadPage("embed", positionSec)
        fetchMetaFallback()
    }

    private fun js(code: String) {
        if (pageReady && !isFinishing) web.evaluateJavascript(code, null)
    }

    /** Messages must not be lost if they happen before the page is ready. */
    private fun jsOrQueue(code: String) {
        if (pageReady) js(code) else pendingMessage = code
    }

    private fun saveProgress(current: Double, duration: Double) {
        // Reset near the end so a finished video starts from the beginning next time.
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
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        js("window.setFullscreenIcon&&setFullscreenIcon($on)")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        js("window.setFullscreenIcon&&setFullscreenIcon($fullscreen)")
    }

    /** Immersive (no status/nav bars) only in fullscreen landscape. */
    @Suppress("DEPRECATION")
    private fun applySystemUi() {
        val immersive = isLandscape()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                if (immersive) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (immersive) (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
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

    private fun backgroundActive() = bgOn && mode == "native" && stream != null

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) {
            if (!backgroundActive()) {
                stream?.pause()
                js("window.pauseVideo&&pauseVideo()")
            }
            web.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onStop() {
        super.onStop()
        if (::web.isInitialized && backgroundActive() && !isChangingConfigurations) {
            stream?.setVideoEnabled(false) // audio only while nothing is on screen
            videoOff = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (videoOff) {
            stream?.setVideoEnabled(true)
            videoOff = false
        }
    }

    override fun onDestroy() {
        Downloads.removeListener(downloadListener)
        stream?.let { PlaybackService.stop(this, it.player) } // only if this screen owns background play
        stream?.release()
        stream = null
        destroyWeb()
        super.onDestroy()
    }

    private fun destroyWeb() {
        if (!::web.isInitialized) return
        web.removeJavascriptInterface("Android")
        web.stopLoading()
        web.loadUrl("about:blank")
        web.destroy()
    }

    // ------------------------------------------------------------------ portrait UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radius).toFloat()
    }

    private fun ripple(content: android.graphics.drawable.Drawable?, mask: android.graphics.drawable.Drawable?) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), content, mask)

    private fun brandText(sizeSp: Float) = TextView(this).apply {
        val name = getString(R.string.app_name)
        val split = name.lastIndexOf(' ').coerceAtLeast(0)
        text = SpannableString(name).apply {
            setSpan(ForegroundColorSpan(Color.parseColor(ACCENT)), split, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildToolbar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor(BG))
        setPadding(dp(6), 0, dp(16), 0)
        addView(ImageView(this@PlayerActivity).apply {
            setImageResource(R.drawable.ic_back)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Back"
            background = ripple(null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(brandText(19f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
    }

    private fun pill(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(16), 0)
        background = ripple(rounded("#272727", 20), rounded("#FFFFFF", 20))
        isClickable = true
        setOnClickListener { onClick() }
        addView(ImageView(this@PlayerActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(TextView(this@PlayerActivity).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
    }

    private fun buildDetails(): ScrollView {
        titleView = TextView(this).apply {
            text = title.ifBlank { "Loading..." }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }
        metaView = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        downloadBtn = pill(R.drawable.ic_download, "Download") { onDownloadClick() }
        bgBtn = pill(R.drawable.ic_headphones, "Background play") { toggleBackground() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(downloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(10) })
            addView(bgBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        }

        cardTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cardClose = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#AAAAAA"))
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            contentDescription = "Close"
            setOnClickListener { onCardClose() }
        }
        cardProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.parseColor(ACCENT))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT))
        }
        cardText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(6), dp(12))
            background = ripple(rounded(CARD, 12), rounded("#FFFFFF", 12))
            visibility = View.GONE
            setOnClickListener { openDownloadedFile() }
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_download)
                    imageTintList = ColorStateList.valueOf(Color.parseColor(ACCENT))
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                addView(cardTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(cardClose, LinearLayout.LayoutParams(dp(36), dp(36)))
            })
            addView(cardProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(2); marginEnd = dp(8)
            })
            addView(cardText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        val tip = TextView(this).apply {
            text = "Tip: turn on Background play, then leave the app or lock the screen. The video keeps playing with controls in your notifications."
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.15f)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            addView(titleView)
            addView(metaView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(View(this@PlayerActivity).apply { setBackgroundColor(Color.parseColor("#262626")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(20) })
            addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
    }

    // ------------------------------------------------------------------ video details

    private fun showInfo(info: StreamInfo) {
        title = info.name.orEmpty().ifBlank { title }
        channel = info.uploaderName.orEmpty()
        val parts = mutableListOf<String>()
        if (channel.isNotBlank()) parts += channel
        if (info.viewCount > 0) parts += "${compact(info.viewCount)} views"
        try {
            info.uploadDate?.offsetDateTime()?.let { d ->
                val date = java.util.Calendar.getInstance().apply { set(d.year, d.monthValue - 1, d.dayOfMonth) }.time
                parts += SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date)
            }
        } catch (_: Exception) {}
        renderMeta(parts.joinToString("  ·  "))
        History.update(this, videoId, title, channel, info.duration)
        if (bgOn) { PlaybackService.title = title; PlaybackService.channel = channel }
    }

    private fun renderMeta(meta: String) {
        titleView.text = title.ifBlank { "Video" }
        metaView.text = meta
        metaView.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }.replace(".0", "")

    /** Title/channel when stream extraction isn't possible (YouTube's public oEmbed API). */
    private fun fetchMetaFallback() {
        if (directUri != null) return
        if (metaFallbackStarted || stream?.currentInfo != null) return
        metaFallbackStarted = true
        Thread {
            try {
                val target = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
                val req = Request.Builder().url("https://www.youtube.com/oembed?format=json&url=$target").build()
                val json = YouTubeStreams.http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                    ?: return@Thread
                val o = JSONObject(json)
                val t = o.optString("title")
                val a = o.optString("author_name")
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (t.isNotBlank()) title = t
                    if (a.isNotBlank()) channel = a
                    renderMeta(channel)
                    History.update(this, videoId, title, channel, 0)
                }
            } catch (_: Exception) {
                // Title is optional; keep what we have.
            }
        }.start()
    }

    /** File name (without extension) of a local video, or the last part of a link. */
    private fun displayName(uri: Uri): String {
        val name = try {
            if (uri.scheme == "content") contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            else uri.lastPathSegment
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment ?: uri.host ?: return "Video"
        return name.substringBeforeLast('.').ifBlank { name }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ background play

    private fun toggleBackground() {
        val s = stream
        if (mode != "native" || s == null) {
            toast("Background play isn't available for this video")
            return
        }
        bgOn = !bgOn
        if (bgOn) {
            try {
                askNotificationPermission()
                PlaybackService.directUri = directUri
                PlaybackService.start(this, s.player, videoId, title, channel)
                toast("Background play on. Leave the app or lock the screen, it keeps playing.")
            } catch (_: Exception) {
                bgOn = false
                toast("Couldn't turn on background play")
            }
        } else {
            PlaybackService.stop(this, s.player)
            toast("Background play off")
        }
        renderBgButton()
    }

    private fun renderBgButton() {
        if (!::bgBtn.isInitialized) return
        val on = bgOn
        bgBtn.background = ripple(rounded(if (on) "#FFFFFF" else "#272727", 20), rounded("#FFFFFF", 20))
        val fg = if (on) Color.parseColor("#0F0F0F") else Color.WHITE
        (bgBtn.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(fg)
        (bgBtn.getChildAt(1) as TextView).apply {
            setTextColor(fg)
            text = if (on) "Background play on" else "Background play"
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || askedNotify) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedNotify = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFY)
    }

    // ------------------------------------------------------------------ downloads

    private fun onDownloadClick() {
        stream?.currentInfo?.let { return showDownloadMenu(it) }
        downloadBtn.isEnabled = false
        downloadBtn.alpha = 0.5f
        toast("Getting download options...")
        Thread {
            val result = runCatching { YouTubeStreams.load(videoId) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                downloadBtn.isEnabled = true
                downloadBtn.alpha = 1f
                result.onSuccess { showDownloadMenu(it) }
                    .onFailure { toast(it.message ?: "Couldn't get download options. Try again.") }
            }
        }.start()
    }

    private fun showDownloadMenu(info: StreamInfo) {
        val options = try { DownloadOptions.from(info) } catch (_: Exception) { emptyList() }
        if (options.isEmpty()) return toast("This video can't be downloaded")
        val popup = PopupMenu(this, downloadBtn, Gravity.START)
        options.forEachIndexed { i, o -> popup.menu.add(0, i, i, o.menuText) }
        popup.setOnMenuItemClickListener { item ->
            options.getOrNull(item.itemId)?.let { startDownload(it) }
            true
        }
        popup.show()
    }

    private fun startDownload(option: DownloadOption) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = option
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), RC_STORAGE)
            return
        }
        askNotificationPermission()
        enqueue(option)
    }

    private fun enqueue(option: DownloadOption) {
        val job = Downloads.create(videoId, title, option) ?: return toast("Already downloading ${option.label}")
        try {
            DownloadService.start(this)
        } catch (_: Exception) {
            Downloads.cancel(job.id)
            return toast("Couldn't start the download. Try again.")
        }
        toast("Downloading ${option.label}")
        renderCard(job)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_STORAGE) {
            // Denied: still download, into the app's own folder.
            pendingDownload?.let { enqueue(it) }
            pendingDownload = null
        }
    }

    private fun renderCard(job: Downloads.Job) {
        if (!::card.isInitialized || isDestroyed) return
        if (job.id != cardJobId && cardJobId != -1 && Downloads.get(cardJobId)?.active == true && !job.active) return
        cardJobId = job.id
        val label = job.option.label
        cardProgress.visibility = View.VISIBLE
        when (job.status) {
            Downloads.Status.QUEUED -> {
                cardTitle.text = "Waiting to download · $label"
                cardProgress.isIndeterminate = true
                cardText.text = "Starts after the current download"
            }
            Downloads.Status.RUNNING -> {
                cardTitle.text = "Downloading · $label"
                val pct = job.percent
                cardProgress.isIndeterminate = pct < 0
                if (pct >= 0) cardProgress.progress = pct
                cardText.text = if (job.total > 0)
                    "$pct%  ·  ${Downloads.formatSize(job.done)} of ${Downloads.formatSize(job.total)}"
                else "Starting..."
            }
            Downloads.Status.SAVING -> {
                cardTitle.text = "Saving · $label"
                cardProgress.isIndeterminate = true
                cardText.text = "Almost done..."
            }
            Downloads.Status.DONE -> {
                cardTitle.text = "Downloaded · $label"
                cardProgress.visibility = View.GONE
                cardText.text = "Saved to ${job.location}. Tap to open."
            }
            Downloads.Status.FAILED -> {
                cardTitle.text = "Download failed · $label"
                cardProgress.visibility = View.GONE
                cardText.text = job.error.ifBlank { "Please try again." }
            }
            Downloads.Status.CANCELLED -> {
                card.visibility = View.GONE
                return
            }
        }
        card.visibility = View.VISIBLE
    }

    private fun onCardClose() {
        val job = Downloads.get(cardJobId)
        if (job != null && job.active) {
            Downloads.cancel(job.id)
            toast("Download cancelled")
        }
        card.visibility = View.GONE
    }

    private fun openDownloadedFile() {
        val job = Downloads.get(cardJobId) ?: return
        val uri = job.resultUri?.takeIf { job.status == Downloads.Status.DONE && it.scheme == "content" } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (job.option.audioOnly) "audio/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast("No app found to open this file")
        }
    }
}
