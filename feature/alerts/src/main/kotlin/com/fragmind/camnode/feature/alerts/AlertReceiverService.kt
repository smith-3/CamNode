package com.fragmind.camnode.feature.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.fragmind.camnode.core.data.locale.AppLanguage
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.DirectorySync
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.di.ApplicationScope
import com.fragmind.camnode.core.data.node.AlertSink
import com.fragmind.camnode.core.data.node.LocalNodeServer
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.network.LanDiscovery
import com.fragmind.camnode.core.network.LocalAddressProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Makes this phone a "master": it keeps the control server up so cameras can push
 * alerts, announces itself as a receiver, and turns each alert into a notification.
 */
@AndroidEntryPoint
class AlertReceiverService : LifecycleService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    @Inject lateinit var households: HouseholdRepository
    @Inject lateinit var alerts: AlertsRepository
    @Inject lateinit var directorySync: DirectorySync
    @Inject lateinit var addresses: LocalAddressProvider
    @Inject lateinit var lanDiscovery: LanDiscovery
    @Inject lateinit var nodeServer: LocalNodeServer
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var runtime: Job? = null

    private val sink = AlertSink { event ->
        if (alerts.record(event)) notifyAlert(event)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runtime == null) runtime = lifecycleScope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime?.cancel()
        appScope.launch { nodeServer.detachAlertSink() }
        super.onDestroy()
    }

    private suspend fun run() {
        val session = households.currentSession() ?: return stopSelf()
        // Muted here, or the role no longer allows viewing: stop serving alerts right away.
        lifecycleScope.launch { alerts.receiverEnabled.first { enabled -> !enabled }; stopSelf() }
        nodeServer.attachAlertSink(sink)
        val discovered = MutableStateFlow<Map<NodeId, NodeEndpoint>>(emptyMap())
        lifecycleScope.launch { lanDiscovery.discover(session.household).collect { discovered.value = it } }
        while (lifecycleScope.isActive) {
            val endpoints = addresses.current().endpoints
            if (endpoints.isNotEmpty()) households.announceSelf(endpoints)
            directorySync.sync(discovered.value.values)
            delay(SYNC_INTERVAL_MILLIS)
        }
    }

    private fun startInForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildOngoingNotification(), type)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground start refused", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground start refused", e)
            false
        }
    }

    private fun buildOngoingNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RECEIVER_CHANNEL, getString(R.string.alerts_channel_master), NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, RECEIVER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(getString(R.string.alerts_receiving))
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun notifyAlert(event: CameraEvent) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.alerts_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                description = getString(R.string.alerts_channel_alerts_description)
            },
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(event.title(this))
            .setContentText(getString(R.string.alerts_check_camera))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(event.atMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            manager.notify(event.id.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val TAG = "AlertReceiverService"
        private const val RECEIVER_CHANNEL = "alert_receiver"
        private const val ALERT_CHANNEL = "camera_alerts"
        private const val NOTIFICATION_ID = 2
        private const val SYNC_INTERVAL_MILLIS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlertReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertReceiverService::class.java))
        }
    }
}

internal fun CameraEvent.title(context: Context): String = when (type) {
    CameraEventType.MOVED -> context.getString(R.string.alerts_camera_moved, cameraName)
}
