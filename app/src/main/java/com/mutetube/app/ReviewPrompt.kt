package com.mutetube.app

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Google Play's in-app rating card. Asked after the 2nd video, and once more after the 10th
 * (Play itself limits how often the card really appears, and never shows it twice in a row).
 */
object ReviewPrompt {
    private const val PREFS = "review"
    private const val KEY_PLAYED = "played"
    private const val KEY_ASKED_AT = "asked_at"
    private val ASK_AFTER = listOf(2, 10)

    /** Call once per video opened. */
    fun onVideoPlayed(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_PLAYED, p.getInt(KEY_PLAYED, 0) + 1).apply()
    }

    /** Call when the home screen comes back: shows the card if it's time. */
    fun maybeAsk(activity: Activity) {
        val p = prefs(activity)
        val played = p.getInt(KEY_PLAYED, 0)
        val askedAt = p.getInt(KEY_ASKED_AT, 0)
        val due = ASK_AFTER.lastOrNull { played >= it } ?: return
        if (askedAt >= due) return
        p.edit().putInt(KEY_ASKED_AT, due).apply() // never nag, even if Play skips the card
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result)
            Telemetry.event(activity, "review_prompt")
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
