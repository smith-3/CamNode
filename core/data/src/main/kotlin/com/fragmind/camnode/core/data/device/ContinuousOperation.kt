package com.fragmind.camnode.core.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A manufacturer screen that decides whether apps may keep running in the background. */
data class VendorStartupManager(val brand: String, val intent: Intent)

/**
 * What keeps a camera alive for hours: Android's battery exemption plus, on some
 * brands, a separate manufacturer switch that ignores the Android one.
 */
class ContinuousOperation @Inject constructor(@ApplicationContext private val context: Context) {

    fun isBatteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** CamNode is a self-hosted always-on camera: exactly the case this exemption exists for. */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun vendorStartupManager(): VendorStartupManager? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return VENDOR_STARTUP_ACTIONS
            .filter { (brand, _) -> manufacturer.contains(brand) }
            .map { (brand, action) -> VendorStartupManager(brand.replaceFirstChar(Char::uppercase), Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .firstOrNull { it.intent.resolveActivity(context.packageManager) != null }
    }

    private companion object {
        /** Public actions of each brand's app-launch manager. Declared in <queries> for package visibility. */
        val VENDOR_STARTUP_ACTIONS = listOf(
            "honor" to "hihonor.intent.action.HSM_STARTUPAPP_MANAGER",
            "huawei" to "huawei.intent.action.HSM_STARTUPAPP_MANAGER",
        )
    }
}
