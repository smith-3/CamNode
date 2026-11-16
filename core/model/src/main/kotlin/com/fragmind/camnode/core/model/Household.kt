package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The trust domain. The secret admits a device as a camera; anything more needs a role
 * signed by an administrator whose authority traces back to [ownerKey]. Households
 * created before roles have no owner and keep full shared-secret access until replaced.
 */
@Serializable
data class Household(
    val id: HouseholdId,
    val name: String,
    val secret: HouseholdSecret,
    val ownerKey: String? = null,
) {
    val secured: Boolean get() = ownerKey != null

    /**
     * Public tag announced over mDNS so phones only pick up their own household.
     * Derived from the id, never from the secret, and too short to identify the home.
     */
    val discoveryTag: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("camnode:${id.value}".toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
}

/** This phone's identity inside a household. Stable across restarts. */
@Serializable
data class LocalNode(
    val id: NodeId,
    val name: String,
    val publicKey: String? = null,
)
