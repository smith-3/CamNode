package com.fragmind.camnode.feature.node

import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.RtspCredentials
import com.fragmind.camnode.core.model.StreamTicket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Per-viewer RTSP credentials. A viewer gets its own random password after a signed
 * request proved it may watch; asking again returns the same ticket, so its stream URL
 * stays stable and playback never reconnects for a refresh.
 */
internal class TicketBook(
    private val clock: () -> Long,
    private val validityMillis: Long = 12 * 60 * 60_000L,
    private val random: SecureRandom = SecureRandom(),
) {
    private val tickets = mutableMapOf<NodeId, StreamTicket>()

    @Synchronized
    fun issue(viewer: NodeId): StreamTicket {
        val now = clock()
        tickets.values.removeAll { it.expiresAtMillis <= now }
        return tickets.getOrPut(viewer) {
            val bytes = ByteArray(PASSWORD_BYTES).also(random::nextBytes)
            StreamTicket(viewer.value, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now + validityMillis)
        }
    }

    /** The viewer holding these credentials, or null. Compared in constant time. */
    @Synchronized
    fun holder(user: String, password: String): NodeId? {
        val ticket = tickets.values.firstOrNull { it.user == user } ?: return null
        val matches = MessageDigest.isEqual(ticket.password.toByteArray(), password.toByteArray())
        return if (matches && ticket.expiresAtMillis > clock()) NodeId(ticket.user) else null
    }

    @Synchronized
    fun clear() = tickets.clear()

    private companion object {
        const val PASSWORD_BYTES = 24
    }
}

/**
 * Who may open this camera's RTSP stream. [legacySecret] is only set for households
 * created before roles, which still use the shared secret as the RTSP password.
 */
class StreamAccess(
    val legacySecret: HouseholdSecret?,
    /** Re-checked at every connection, so a revoked viewer can't reuse a ticket it still holds. */
    val canView: (NodeId) -> Boolean,
)

internal fun StreamAccess.accepts(tickets: TicketBook, user: String, password: String): Boolean {
    val legacy = legacySecret
    if (legacy != null && user == RtspCredentials.USER && MessageDigest.isEqual(legacy.value.toByteArray(), password.toByteArray())) return true
    val viewer = tickets.holder(user, password) ?: return false
    return canView(viewer)
}
