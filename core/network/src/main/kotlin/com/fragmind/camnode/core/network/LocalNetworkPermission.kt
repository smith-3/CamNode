package com.fragmind.camnode.core.network

import android.os.Build

/**
 * Apps targeting SDK 37 need ACCESS_LOCAL_NETWORK on Android 17+ for any LAN traffic
 * (mDNS, Wi-Fi peers). Older platforms grant it implicitly with INTERNET.
 */
object LocalNetworkPermission {
    private const val ANDROID_17 = 37
    private const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val runtimePermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= ANDROID_17) listOf(PERMISSION) else emptyList()
}
