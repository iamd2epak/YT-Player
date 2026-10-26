package com.mutetube.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Share the app with friends and open its store page. */
object AppShare {
    private const val STORE_URL = "https://play.google.com/store/apps/details?id=com.mutetube.app"

    fun share(context: Context) {
        val text = "Try MuteAd Tube: a simple, ad-free video player. Play video links and files, " +
            "listen in the background and save videos offline. Free download:\n$STORE_URL"
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "MuteAd Tube")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, "Share app"))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Opens the Play Store app, or the store website if it's missing. */
    fun rate(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.mutetube.app"))
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
