package com.fragmind.camnode.core.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class BatteryLevel(val percent: Int?, val charging: Boolean)

class DeviceBattery @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): BatteryLevel {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return BatteryLevel(null, false)
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> manager.isCharging
        }
        return BatteryLevel(percent, charging)
    }
}
