package com.fragmind.camnode.core.data.device

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coarse device tier used to pick cheaper defaults on old phones: lower stream quality,
 * no always-on preview, no live video in the camera grid.
 */
interface DeviceClass {
    val isLowEnd: Boolean
}

@Singleton
internal class AndroidDeviceClass @Inject constructor(@ApplicationContext context: Context) : DeviceClass {
    override val isLowEnd: Boolean = context.getSystemService(ActivityManager::class.java)?.let { manager ->
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        manager.isLowRamDevice || memory.totalMem < LOW_END_MEMORY_BYTES
    } ?: false

    private companion object {
        /** Phones with 2 GB or less of RAM report about 1.7-1.9 GB. */
        const val LOW_END_MEMORY_BYTES = 2_500L * 1024 * 1024
    }
}
