package com.mutetube.app

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Firebase Analytics + Crashlytics. Never sends video titles, links or file names: only which
 * features are used and crash reports. Users can turn it off in Settings. Does nothing when the
 * build has no Firebase config (google-services.json).
 */
object Telemetry {
    private const val PREFS = "telemetry"
    private const val KEY_ENABLED = "enabled"

    fun init(context: Context) = apply(context, isEnabled(context))

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        apply(context, enabled)
    }

    /** Feature usage, e.g. event(ctx, "video_play", "source" to "file"). */
    fun event(context: Context, name: String, vararg params: Pair<String, String>) {
        if (!ready(context) || !isEnabled(context)) return
        try {
            FirebaseAnalytics.getInstance(context).logEvent(name, Bundle().apply { params.forEach { putString(it.first, it.second) } })
        } catch (_: Exception) {
        }
    }

    /** Errors that were handled but are worth knowing about (e.g. a video failed to load). */
    fun log(context: Context, message: String) {
        if (!ready(context) || !isEnabled(context)) return
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (_: Exception) {
        }
    }

    private fun apply(context: Context, enabled: Boolean) {
        if (!ready(context)) return
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
        } catch (_: Exception) {
        }
    }

    private fun ready(context: Context) = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
