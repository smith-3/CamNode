package com.fragmind.camnode.core.data.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Shortcuts to keep Tailscale permanently connected. Android's "Always-on VPN" starts
 * Tailscale at boot and reconnects it when it drops; only the user can enable it.
 */
class TailscaleSetup @Inject constructor(@ApplicationContext private val context: Context) {

    fun isInstalled(): Boolean = openAppIntent() != null

    fun openAppIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun alwaysOnVpnIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Battery and background rules of the Tailscale app itself. */
    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val PACKAGE = "com.tailscale.ipn"
    }
}
