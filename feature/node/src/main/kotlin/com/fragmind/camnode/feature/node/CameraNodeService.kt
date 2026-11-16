package com.fragmind.camnode.feature.node

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.fragmind.camnode.core.data.locale.AppLanguage
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fragmind.camnode.core.data.CameraSettingsRepository
import com.fragmind.camnode.core.data.DirectorySync
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.HouseholdSession
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.StreamTicket
import com.fragmind.camnode.core.data.di.ApplicationScope
import com.fragmind.camnode.core.data.node.CameraCapability
import com.fragmind.camnode.core.data.node.EventDispatcher
import com.fragmind.camnode.core.data.node.LocalNodeServer
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.CameraStatus
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.WallClock
import com.fragmind.camnode.core.network.Advertisement
import com.fragmind.camnode.core.network.LanDiscovery
import com.fragmind.camnode.core.network.LocalAddressProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Keeps a phone working as a camera with the screen off: foreground service, CPU and
 * Wi-Fi locks, the RTSP stream, the control API, movement alerts and periodic gossip.
 */
@AndroidEntryPoint
class CameraNodeService : LifecycleService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    @Inject lateinit var controller: CameraStreamController
    @Inject lateinit var households: HouseholdRepository
    @Inject lateinit var cameraSettings: CameraSettingsRepository
    @Inject lateinit var directorySync: DirectorySync
    @Inject lateinit var addresses: LocalAddressProvider
    @Inject lateinit var lanDiscovery: LanDiscovery
    @Inject lateinit var nodeServer: LocalNodeServer
    @Inject lateinit var events: EventDispatcher
    @Inject lateinit var movement: MovementDetector
    @Inject lateinit var clock: WallClock
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var advertisement: Advertisement? = null
    private var runtime: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Latest session; RTSP connections check a viewer's current role against it. */
    @Volatile private var session: HouseholdSession? = null

    private val camera = object : CameraCapability {
        override fun status(): CameraStatus? = controller.cameraStatus()
        override fun issueTicket(viewer: NodeId): StreamTicket? = controller.issueTicket(viewer)
        override suspend fun apply(command: CameraCommand): CameraStatus? {
            val status = withContext(Dispatchers.Main) { controller.apply(command) }
            cameraSettings.update { settings ->
                settings.copy(
                    facing = command.facing ?: settings.facing,
                    orientation = command.orientation ?: settings.orientation,
                    imageMode = command.imageMode ?: settings.imageMode,
                )
            }
            return status
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            appScope.launch { cameraSettings.setCameraShouldRun(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasPermission(Manifest.permission.CAMERA) || !startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runtime == null) runtime = lifecycleScope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime?.cancel()
        movement.stop()
        advertisement?.stop()
        appScope.launch { nodeServer.detachCamera() }
        controller.stop()
        releaseLocks()
        super.onDestroy()
    }

    private suspend fun run() {
        val session = households.currentSession() ?: return stopSelf()
        val stored = cameraSettings.settings.first()
        val settings = stored.copy(audioEnabled = stored.audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO))
        this.session = session
        lifecycleScope.launch { households.session.collect { this@CameraNodeService.session = it } }
        if (!controller.start(settings, streamAccess(session))) return stopSelf()
        cameraSettings.setCameraShouldRun(true)
        lifecycleScope.launch {
            controller.state.first { it is StreamState.Failed }
            stopSelf()
        }
        acquireLocks()
        lifecycleScope.launch { holdWifiWhileWatched() }
        lifecycleScope.launch { reflectStateInNotification() }
        nodeServer.attachCamera(camera)
        advertisement = lanDiscovery.advertise(session.self.id, session.household)
        if (settings.movementAlerts) {
            movement.start {
                lifecycleScope.launch {
                    val current = households.currentSession() ?: return@launch
                    if (current.household.id == session.household.id) {
                        events.dispatch(CameraEvent.new(current.self.id, current.self.name, CameraEventType.MOVED, clock.nowMillis()))
                    }
                }
            }
        }
        while (lifecycleScope.isActive) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            val endpoints = addresses.current().endpoints
            if (endpoints.isNotEmpty()) households.announceSelf(endpoints, NodeRole.CAMERA)
            directorySync.sync()
            delay(SYNC_INTERVAL_MILLIS)
        }
    }

    /**
     * Legacy households keep the shared secret as RTSP password. Secured ones only accept
     * per-viewer tickets, re-checking the viewer's role at every connection.
     */
    private fun streamAccess(start: HouseholdSession) = StreamAccess(
        legacySecret = start.household.secret.takeUnless { start.household.secured },
        canView = { viewer ->
            val current = session ?: start
            val key = current.directory[viewer]?.publicKey
            current.directory.roles.roleOf(viewer, key, current.household.ownerKey).can(Permission.VIEW_STREAMS)
        },
    )

    /** A camera service may only start while the app is visible; a sticky restart from the background is refused. */
    private fun startInForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val microphone = if (hasPermission(Manifest.permission.RECORD_AUDIO)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or microphone
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground start refused", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground start refused", e)
            false
        }
    }

    /** Keeps the ongoing notification honest: paused cameras say so. */
    private suspend fun reflectStateInNotification() {
        controller.state
            .map { it is StreamState.Paused }
            .distinctUntilChanged()
            .collect { paused -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(paused)) }
    }

    private fun buildNotification(paused: Boolean = false): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.node_channel_active), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CameraNodeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camnode)
            .setContentTitle(getString(if (paused) R.string.node_paused_title else R.string.node_streaming_title))
            .setContentText(getString(if (paused) R.string.node_paused_notification else R.string.node_streaming_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.node_stop_short), stop)
            .build()
    }

    /** The wake lock expires on its own unless the sync loop renews it, so a stuck process cannot drain the battery. */
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamNode:stream")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(wifiMode, "CamNode:stream")
            .apply { setReferenceCounted(false) }
    }

    /** Low-latency Wi-Fi only while someone watches; otherwise the radio may use its power-save mode. */
    private suspend fun holdWifiWhileWatched() {
        controller.state
            .map { (it as? StreamState.Live)?.idle == false }
            .distinctUntilChanged()
            .collect { watched ->
                val lock = wifiLock ?: return@collect
                if (watched) lock.acquire() else if (lock.isHeld) lock.release()
            }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CameraNodeService"
        private const val CHANNEL_ID = "camera_node"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.fragmind.camnode.node.STOP"

        /** Sent to the app's launcher activity, which may start a camera service because it is visible. */
        const val ACTION_RESUME = "com.fragmind.camnode.node.RESUME"
        private const val SYNC_INTERVAL_MILLIS = 60_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraNodeService::class.java))
        }

        /** A user stop: unlike a reboot or a system kill, it also forgets that the camera should run. */
        fun stop(context: Context) {
            context.startService(Intent(context, CameraNodeService::class.java).setAction(ACTION_STOP))
        }
    }
}
