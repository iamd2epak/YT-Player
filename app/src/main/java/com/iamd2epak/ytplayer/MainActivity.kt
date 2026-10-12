package com.iamd2epak.ytplayer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, dp(8), 0, dp(16))
        })

        // ---- Paste a YouTube link and play ----
        val input = EditText(this).apply {
            hint = "Paste YouTube link"
            setHintTextColor(Color.parseColor("#888899"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#2A2A3C")
        }
        val paste = button("Paste", "#3A3A50") {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) toast("Clipboard is empty") else input.setText(text.trim())
        }
        val play = button("Play", "#E53935") { playLink(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { playLink(input.text.toString()); true } else false
        }

        column.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, dp(20))
            addView(paste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(10) })
            addView(play, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        })

        // ---- Course lectures ----
        column.addView(TextView(this).apply {
            text = "Lectures"
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(8))
        })
        Lectures.all.forEach { lecture ->
            column.addView(TextView(this).apply {
                text = "▶   ${lecture.title}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(64)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded("#2A2A3C")
                isClickable = true
                setOnClickListener { open(lecture.video, lecture.title) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            fitsSystemWindows = true // keep content clear of status/nav bars (Android 15 edge-to-edge)
            addView(column)
        })
    }

    private fun playLink(text: String) {
        if (text.isBlank()) return toast("Paste a YouTube link first")
        if (YouTubeId.parse(text) == null) return toast("That doesn't look like a YouTube link")
        open(text.trim(), "")
    }

    private fun open(video: String, title: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO, video)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
        )
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
