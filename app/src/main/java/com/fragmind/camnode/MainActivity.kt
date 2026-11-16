package com.fragmind.camnode

import android.content.Context
import android.content.Intent
import com.fragmind.camnode.core.data.locale.AppLanguage
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.AppLockRepository
import com.fragmind.camnode.feature.applock.AppLockGate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.fragmind.camnode.feature.alerts.AlertReceiverService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.fragmind.camnode.core.model.InviteCodec
import com.fragmind.camnode.feature.node.CameraNodeService
import com.fragmind.camnode.feature.node.CameraResumeReceiver
import com.fragmind.camnode.navigation.CamNodeNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {
    @Inject lateinit var alerts: AlertsRepository
    @Inject lateinit var appLock: AppLockRepository

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /** An invite link opened from outside the app (system camera, chat app). */
    private val incomingInvite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            incomingInvite.value = intent.inviteLink()
            resumeCameraIfAsked(intent)
        }
        hideFromRecentsWhileLocked()
        // Before Android 13 the chosen language comes from a wrapped context, which the window
        // does not use for its layout direction; Arabic needs right-to-left set explicitly.
        val direction = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                CamNodeTheme {
                    AppLockGate {
                        CamNodeNavHost(
                            incomingInvite = incomingInvite.value,
                            onInviteConsumed = { incomingInvite.value = null },
                        )
                    }
                }
            }
        }
    }

    /**
     * With the PIN lock on, the recents thumbnail must not show cameras. Android 13+ can
     * hide just the thumbnail; older versions need FLAG_SECURE, which also blocks screenshots.
     */
    private fun hideFromRecentsWhileLocked() {
        lifecycleScope.launch {
            appLock.config.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!enabled)
                else if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /** Safety net: a visible activity may (re)start the master service if the system stopped it. */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            if (alerts.receiverEnabled.first()) AlertReceiverService.start(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.inviteLink()?.let { incomingInvite.value = it }
        resumeCameraIfAsked(intent)
    }

    /** From the "resume camera" notification: the activity is visible, so a camera service may start. */
    private fun resumeCameraIfAsked(intent: Intent) {
        if (intent.action != CameraNodeService.ACTION_RESUME) return
        CameraNodeService.start(this)
        NotificationManagerCompat.from(this).cancel(CameraResumeReceiver.NOTIFICATION_ID)
    }

    private fun Intent.inviteLink(): String? = data?.takeIf { it.scheme == InviteCodec.SCHEME }?.toString()
}
