package com.fragmind.camnode.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.fragmind.camnode.core.model.CamNodePorts
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

fun interface Advertisement {
    fun stop()
}

/**
 * Finds household cameras on the same Wi-Fi through mDNS (`_camnode._tcp`). Discovery
 * only proposes addresses: every call to a found node is still authorized by the secret.
 */
@Singleton
class LanDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    /** Before Android 14 NSD rejects a second resolve while one is running. */
    private val legacyResolveLock = Mutex()

    fun advertise(nodeId: NodeId, household: Household): Advertisement {
        val info = NsdServiceInfo().apply {
            serviceName = "CamNode-${nodeId.value.take(8)}"
            serviceType = SERVICE_TYPE
            port = CamNodePorts.CONTROL
            setAttribute(ATTR_NODE, nodeId.value)
            setAttribute(ATTR_HOUSEHOLD, household.discoveryTag)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Announced ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Announce failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Advertisement { runCatching { nsd.unregisterService(listener) } }
    }

    /** Control endpoints of [household] cameras announcing on this network, keyed by node. */
    fun discover(household: Household): Flow<Map<NodeId, NodeEndpoint>> = callbackFlow {
        val found = ConcurrentHashMap<String, Pair<NodeId, NodeEndpoint>>()
        val publish = { trySend(found.values.toMap()) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                launch {
                    val match = resolve(serviceInfo)?.toHouseholdMatch(household) ?: return@launch
                    found[serviceInfo.serviceName] = match
                    publish()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (found.remove(serviceInfo.serviceName) != null) publish()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        trySend(emptyMap())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(info) else resolveLegacy(info)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.hostAddresses.isEmpty() || !continuation.isActive) return
                continuation.resume(serviceInfo)
                runCatching { nsd.unregisterServiceInfoCallback(this) }
            }
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(info, executor, callback)
        continuation.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
    }

    @Suppress("DEPRECATION") // resolveService is the only resolver before Android 14.
    private suspend fun resolveLegacy(info: NsdServiceInfo): NsdServiceInfo? = legacyResolveLock.withLock {
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = continuation.resume(serviceInfo)
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = continuation.resume(null)
            })
        }
    }

    private fun NsdServiceInfo.toHouseholdMatch(household: Household): Pair<NodeId, NodeEndpoint>? {
        val tag = attributes[ATTR_HOUSEHOLD]?.decodeToString()
        val node = attributes[ATTR_NODE]?.decodeToString()?.takeIf(String::isNotBlank) ?: return null
        if (tag != household.discoveryTag) return null
        val address = hostAddress() ?: return null
        return NodeId(node) to NodeEndpoint(address, controlPort = port)
    }

    @Suppress("DEPRECATION") // host is the only address accessor before Android 14.
    private fun NsdServiceInfo.hostAddress(): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses else listOfNotNull(host)
        val preferred = addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.firstOrNull()
        return preferred?.hostAddress?.substringBefore('%')
    }

    private companion object {
        const val TAG = "LanDiscovery"
        const val SERVICE_TYPE = "_camnode._tcp"
        const val ATTR_NODE = "n"
        const val ATTR_HOUSEHOLD = "h"
    }
}
