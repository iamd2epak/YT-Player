package com.iamd2epak.ytplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val ACCENT = "#E53935"
        const val CARD = "#2A2A3C"
        const val RC_PICK = 21
    }

    private val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

    private lateinit var historyList: LinearLayout
    private lateinit var clearAll: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // ---- Brand header ----
        column.addView(TextView(this).apply {
            val name = getString(R.string.app_name)
            val split = name.lastIndexOf(' ').coerceAtLeast(0)
            text = SpannableString(name).apply {
                setSpan(ForegroundColorSpan(Color.parseColor(ACCENT)), split, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "Simple, ad-free video player. Open a video, paste a link, or share one to this app."
            setTextColor(Color.parseColor("#9A9AAE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(18))
        })

        // ---- Open a video file ----
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), rounded(ACCENT), rounded("#FFFFFF"))
            isClickable = true
            setOnClickListener { pickVideo() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = "Open video from phone"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(14) })

        // ---- Paste a video link and play ----
        val input = EditText(this).apply {
            hint = "Paste video link"
            setHintTextColor(Color.parseColor("#888899"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD)
        }
        val paste = button("Paste", "#3A3A50") {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) toast("Clipboard is empty") else input.setText(text.trim())
        }
        val play = button("Play", "#3A3A50") { playLink(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { playLink(input.text.toString()); true } else false
        }

        column.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, dp(22))
            addView(paste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(10) })
            addView(play, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        })

        // ---- History ----
        clearAll = TextView(this).apply {
            text = "Clear all"
            setTextColor(Color.parseColor("#FF6B66"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(4), dp(6))
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null, rounded("#FFFFFF"))
            setOnClickListener { confirmClear() }
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_history)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#AAAAAA"))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = "History"
                setTextColor(Color.parseColor("#DDDDDD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearAll)
        })
        empty = TextView(this).apply {
            text = "No videos yet.\nOpen a video, paste a link, or share a video to MuteAd Tube."
            setTextColor(Color.parseColor("#888899"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(16), dp(36), dp(16), dp(36))
        }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(empty)
        column.addView(historyList)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            fitsSystemWindows = true // keep content clear of status/nav bars (Android 15 edge-to-edge)
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun renderHistory() {
        val items = History.all(this)
        historyList.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearAll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { historyList.addView(row(it)) }
    }

    private fun row(item: History.Item): View {
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33334A"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
            }
            clipToOutline = true
        }
        Thumbs.load(thumb, item.id, item.uri)
        val thumbBox = FrameLayout(this).apply {
            addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (item.durationSec > 0) addView(TextView(this@MainActivity).apply {
                text = DateUtils.formatElapsedTime(item.durationSec)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = GradientDrawable().apply { setColor(Color.parseColor("#CC000000")); cornerRadius = dp(4).toFloat() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = item.title.ifBlank { "Video" }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@MainActivity).apply {
                val ago = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                text = listOf(item.channel, ago.toString()).filter { it.isNotBlank() }.joinToString("  ·  ")
                setTextColor(Color.parseColor("#9A9AAE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }

        val remove = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#77778A"))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Remove"
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener {
                History.remove(this@MainActivity, item.id)
                renderHistory()
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(2), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), rounded(CARD), rounded("#FFFFFF"))
            isClickable = true
            setOnClickListener { open(item) }
            addView(thumbBox, LinearLayout.LayoutParams(dp(128), dp(72)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            addView(remove, LinearLayout.LayoutParams(dp(36), dp(36)))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("This removes all videos from your history.")
            .setPositiveButton("Clear") { _, _ ->
                History.clear(this)
                renderHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playLink(text: String) {
        if (text.isBlank()) return toast("Paste a video link first")
        val intent = LinkActivity.playerIntentForText(this, text.trim()) ?: return toast("That doesn't look like a video link")
        start(intent)
    }

    private fun open(item: History.Item) {
        val intent = Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        if (item.uri.isNotBlank()) intent.setData(Uri.parse(item.uri)) else intent.putExtra(PlayerActivity.EXTRA_VIDEO, item.id)
        start(intent)
    }

    private fun start(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Can't open this video") }
    }

    private fun pickVideo() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(pick, RC_PICK)
        } catch (_: Exception) {
            toast("No file manager found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != RC_PICK || resultCode != RESULT_OK || uri == null) return
        // Keep access so the video can be reopened from History later.
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        start(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minWidth = dp(88)
        background = rounded(color)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(10).toFloat()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
