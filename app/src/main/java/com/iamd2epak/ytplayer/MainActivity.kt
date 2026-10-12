package com.iamd2epak.ytplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

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

        Lectures.all.forEach { lecture ->
            column.addView(TextView(this).apply {
                text = "▶   ${lecture.title}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(64)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(Color.parseColor("#2A2A3C"))
                isClickable = true
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_VIDEO, lecture.video)
                            .putExtra(PlayerActivity.EXTRA_TITLE, lecture.title)
                    )
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            fitsSystemWindows = true // keep content clear of status/nav bars (Android 15 edge-to-edge)
            addView(column)
        })
    }
}
