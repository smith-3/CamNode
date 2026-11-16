package com.fragmind.camnode.feature.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A master phone keeps receiving alerts after a reboot or an app update without opening the app. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var alerts: AlertsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        appScope.launch {
            try {
                if (alerts.receiverEnabled.first()) AlertReceiverService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
