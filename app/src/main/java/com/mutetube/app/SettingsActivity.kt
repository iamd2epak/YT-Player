package com.mutetube.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** About, policies, licenses and data controls. */
class SettingsActivity : Activity() {

    companion object {
        const val PRIVACY_URL = "https://github.com/iamd2epak/YT-Player/blob/main/PRIVACY.md"
        const val TERMS_URL = "https://github.com/iamd2epak/YT-Player/blob/main/TERMS.md"
        const val SOURCE_URL = "https://github.com/iamd2epak/YT-Player"
        const val CONTACT_EMAIL = "99.iamdeepak@gmail.com"
        private const val CARD = "#2A2A3C"
    }

    private val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }

        // ---- Toolbar ----
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_back)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Back"
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(this@SettingsActivity).apply {
                text = "Settings"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        section(column, "Data")
        column.addView(row("Clear history", "Remove all videos from your history") { confirmClear() })

        section(column, "Spread the word")
        column.addView(row("Share app", "Send the app link to friends") { AppShare.share(this) })
        column.addView(row("Rate the app", "A quick rating helps a lot") { AppShare.rate(this) })

        section(column, "About")
        column.addView(row("Privacy policy", "What data the app uses (none is collected)") { openUrl(PRIVACY_URL) })
        column.addView(row("Terms of use", "Rules for using this app") { openUrl(TERMS_URL) })
        column.addView(row("Contact & feedback", CONTACT_EMAIL) { email() })
        column.addView(row("Open-source licenses", "Libraries used by this app") { licenses() })
        column.addView(row("Source code", "Free software under GPL-3.0") { openUrl(SOURCE_URL) })
        column.addView(row("Version", versionName(), null))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            fitsSystemWindows = true
            addView(column)
        })
    }

    private fun section(column: LinearLayout, title: String) = column.addView(TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#9D86FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(18), dp(8), dp(8))
    })

    private fun row(title: String, subtitle: String, onClick: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val bg = GradientDrawable().apply { setColor(Color.parseColor(CARD)); cornerRadius = dp(10).toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
        background = if (onClick != null) RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), bg, mask) else bg
        if (onClick != null) setOnClickListener { onClick() }
        addView(TextView(this@SettingsActivity).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        addView(TextView(this@SettingsActivity).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9A9AAE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("This removes all videos from your history.")
            .setPositiveButton("Clear") { _, _ ->
                History.clear(this)
                toast("History cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun licenses() {
        AlertDialog.Builder(this)
            .setTitle("Open-source licenses")
            .setMessage(
                "${getString(R.string.app_name)} is free software licensed under the GNU GPL v3.0.\n\n" +
                "• NewPipe Extractor — GPL-3.0\n" +
                "• AndroidX Media3 (ExoPlayer) — Apache 2.0\n" +
                "• OkHttp — Apache 2.0\n" +
                "• Mozilla Rhino — MPL 2.0\n\n" +
                "Full source code and license texts are available at:\n$SOURCE_URL"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("View source") { _, _ -> openUrl(SOURCE_URL) }
            .show()
    }

    private fun email() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
            .putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} feedback (v${versionName()})")
        try { startActivity(intent) } catch (_: Exception) { toast(CONTACT_EMAIL) }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("No browser found") }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
