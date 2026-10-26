package com.mutetube.app

import android.app.Activity
import android.app.AlertDialog
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Play Store in-app updates. When a newer version is live on Play, the app shows Google's update
 * dialog: a normal update downloads in the background and asks to restart when ready; a high
 * priority (set in the Play Console, 4-5) or 14+ days old update is installed full screen.
 * Does nothing for builds not installed from Play (sideloaded APKs).
 */
class AppUpdater(private val activity: Activity) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var readyDialog: AlertDialog? = null

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) showRestart()
    }

    /** Call once from onCreate. */
    fun check() {
        manager.registerListener(listener)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (activity.isFinishing || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
            val urgent = info.updatePriority() >= 4 || (info.clientVersionStalenessDays() ?: 0) >= 14
            val type = when {
                urgent && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                else -> return@addOnSuccessListener
            }
            start(info, type)
        }
    }

    /** Call from onResume: finish an update that was downloaded or interrupted meanwhile. */
    fun resume() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> showRestart()
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    start(info, AppUpdateType.IMMEDIATE)
            }
        }
    }

    /** Call from onDestroy. */
    fun release() {
        manager.unregisterListener(listener)
        readyDialog?.dismiss()
    }

    private fun start(info: com.google.android.play.core.appupdate.AppUpdateInfo, type: Int) {
        try {
            manager.startUpdateFlowForResult(info, activity, AppUpdateOptions.newBuilder(type).build(), REQUEST_CODE)
        } catch (_: Exception) {
            // Play Store missing or busy: try again next launch.
        }
    }

    private fun showRestart() {
        if (activity.isFinishing || readyDialog?.isShowing == true) return
        readyDialog = AlertDialog.Builder(activity)
            .setTitle("Update ready")
            .setMessage("A new version of the app has been downloaded. Restart to finish updating.")
            .setPositiveButton("Restart") { _, _ -> manager.completeUpdate() }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        private const val REQUEST_CODE = 4201
    }
}
