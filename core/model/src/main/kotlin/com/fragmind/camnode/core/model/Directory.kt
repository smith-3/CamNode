package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class NodeRole { CAMERA, VIEWER, RECORDER }

/**
 * One device as known by the household, with every address it announced (LAN and
 * tailnet). [updatedAtMillis] is set by the device that
 * owns the record (or by whoever renamed or removed it), and decides which copy wins a merge.
 * A removal is a tombstone so it propagates instead of being resurrected by a peer.
 */
@Serializable
data class Member(
    val id: NodeId,
    val name: String,
    val role: NodeRole,
    val endpoints: List<NodeEndpoint> = emptyList(),
    val updatedAtMillis: Long,
    val removed: Boolean = false,
    /** This node wants camera alerts, such as the phone you carry or the recorder PC. */
    val receivesAlerts: Boolean = false,
    /** Self-announced; roles bind to it so a copied node id can't borrow someone's role. */
    val publicKey: String? = null,
)

/**
 * The household's replicated device list. [merge] is commutative, associative and
 * idempotent (per-member last-writer-wins with a deterministic tie-break), so peers
 * can exchange directories in any order, any number of times, and converge.
 */
@Serializable
data class Directory(val members: List<Member> = emptyList(), val roles: RoleBook = RoleBook()) {

    val active: List<Member> get() = members.filterNot(Member::removed)

    /** Includes cameras with no known address: a security app must show a camera that went dark. */
    val cameras: List<Member> get() = active.filter { it.role == NodeRole.CAMERA }

    val alertReceivers: List<Member> get() = active.filter { it.receivesAlerts && it.endpoints.isNotEmpty() }

    operator fun get(id: NodeId): Member? = members.firstOrNull { it.id == id }

    /**
     * [ownerKey] decides which role grants are trustworthy, so every caller must pass the
     * household's; a legacy household (null) has no roles to keep.
     */
    fun merge(other: Directory, ownerKey: String?): Directory {
        val winners = (members + other.members)
            .groupBy(Member::id)
            .map { (_, candidates) -> candidates.maxWith(MemberPrecedence) }
            .sortedBy { it.id.value }
        return Directory(winners, roles.merge(other.roles, ownerKey))
    }

    fun withRoles(roles: RoleBook): Directory = copy(roles = roles)

    /** Replaces one member record by precedence; role grants are untouched. */
    fun upsert(member: Member): Directory = copy(members = merge(Directory(listOf(member)), ownerKey = null).members)
}

private val MemberPrecedence: Comparator<Member> = compareBy<Member>(
    { it.updatedAtMillis },
    { it.removed },
    { it.name },
    { it.role },
    { it.receivesAlerts },
    { it.publicKey },
    { it.endpoints.joinToString(",") { endpoint -> "${endpoint.host}:${endpoint.controlPort}:${endpoint.rtspPort}" } },
)
