package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable

enum class Permission {
    /** Watch live video and receive alerts. */
    VIEW_STREAMS,
    /** Change lens, zoom, image mode, torch; pause and resume cameras. */
    CONTROL_CAMERAS,
    /** Invite devices, assign roles, revoke access. */
    MANAGE_HOUSEHOLD,
}

/**
 * What a device may do in the household, independent of what it does ([NodeRole]).
 * Everyone joins as [CAMERA]; only an administrator can raise or revoke a role.
 */
@Serializable
enum class AccessRole(val permissions: Set<Permission>) {
    ADMIN(Permission.entries.toSet()),
    VIEWER(setOf(Permission.VIEW_STREAMS)),
    RECORDER(setOf(Permission.VIEW_STREAMS)),
    CAMERA(emptySet()),
    /** Blocked: the device's requests are refused even if it still holds the household secret. */
    REVOKED(emptySet());

    fun can(permission: Permission): Boolean = permission in permissions
}

/** An administrator's signed statement that [nodeId] (holding [nodeKey]) has [role]. */
@Serializable
data class RoleGrant(
    val nodeId: NodeId,
    val nodeKey: String,
    val role: AccessRole,
    val issuedAtMillis: Long,
    val issuerKey: String,
    val signature: String = "",
) {
    fun payload(): ByteArray =
        "camnode-grant-v1|${nodeId.value}|$nodeKey|${role.name}|$issuedAtMillis|$issuerKey".toByteArray()

    fun signedBy(issuer: NodeKeyPair): RoleGrant = copy(issuerKey = issuer.publicKey, signature = NodeKeys.sign(issuer.privateKey, payload()))

    fun hasValidSignature(): Boolean = NodeKeys.verify(issuerKey, payload(), signature)
}

/**
 * The household's replicated role assignments. Only grants that verify are kept, so a
 * forged or out-of-authority grant can neither raise a role nor shadow a real one.
 *
 * Authority: the owner (whose key is pinned in the household) may grant any role.
 * Administrators appointed by the owner may grant everything except [AccessRole.ADMIN],
 * and may not touch the owner or other administrators. The latest valid grant wins.
 */
@Serializable
data class RoleBook(val grants: List<RoleGrant> = emptyList()) {

    fun merge(other: RoleBook, ownerKey: String?): RoleBook {
        if (ownerKey == null) return RoleBook()
        val candidates = (grants + other.grants).distinct().filter(RoleGrant::hasValidSignature)
        val ownerSigned = candidates.filter { it.issuerKey == ownerKey }.latestPerNode()
        val adminKeys = ownerSigned.values.filter { it.role == AccessRole.ADMIN }.map(RoleGrant::nodeKey).toSet()
        val adminSigned = candidates.filter { grant ->
            grant.issuerKey in adminKeys &&
                grant.role != AccessRole.ADMIN &&
                grant.nodeKey != ownerKey &&
                ownerSigned[grant.nodeId]?.role != AccessRole.ADMIN
        }
        val effective = (ownerSigned.values + adminSigned).latestPerNode()
        return RoleBook(effective.values.sortedBy { it.nodeId.value })
    }

    /**
     * The role of a caller identified by [nodeId] and proven to hold [nodeKey]. A legacy
     * household without an owner keeps the old shared-secret behaviour (full access).
     */
    fun roleOf(nodeId: NodeId, nodeKey: String?, ownerKey: String?): AccessRole {
        if (ownerKey == null) return AccessRole.ADMIN
        if (nodeKey != null && nodeKey == ownerKey) return AccessRole.ADMIN
        val grant = grants.firstOrNull { it.nodeId == nodeId } ?: return AccessRole.CAMERA
        return when {
            grant.role == AccessRole.REVOKED -> AccessRole.REVOKED
            grant.nodeKey == nodeKey -> grant.role
            else -> AccessRole.CAMERA
        }
    }

    private fun List<RoleGrant>.latestPerNode(): Map<NodeId, RoleGrant> =
        groupBy(RoleGrant::nodeId).mapValues { (_, list) -> list.maxWith(compareBy({ it.issuedAtMillis }, { it.signature })) }
}

/**
 * Short-lived RTSP credentials a camera hands to a caller allowed to watch. The camera
 * accepts one ticket per viewer, so the household secret alone never opens a video stream.
 */
@Serializable
data class StreamTicket(val user: String, val password: String, val expiresAtMillis: Long)
