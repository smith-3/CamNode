package com.fragmind.camnode.feature.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fragmind.camnode.core.data.CameraSettingsRepository
import com.fragmind.camnode.core.data.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android forbids starting a camera service at boot. When the user had left the camera
 * on, a reboot or app update posts a notification; one tap resumes the stream.
 */
@AndroidEntryPoint
class CameraResumeReceiver : BroadcastReceiver() {
    @Inject lateinit var cameraSettings: CameraSettingsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        appScope.launch {
            try {
                if (cameraSettings.cameraShouldRun.first()) notifyResume(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyResume(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.node_channel_resume), NotificationManager.IMPORTANCE_HIGH),
        )
        val resume = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setAction(CameraNodeService.ACTION_RESUME)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camnode)
            .setContentTitle(context.getString(R.string.node_camera_stopped))
            .setContentText(context.getString(R.string.node_tap_to_resume))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications revoked; the user can still resume from the app.
        }
    }

    companion object {
        private const val CHANNEL_ID = "camera_resume"
        const val NOTIFICATION_ID = 3
    }
}
