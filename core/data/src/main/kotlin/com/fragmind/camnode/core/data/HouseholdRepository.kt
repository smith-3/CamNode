package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.control.ControlAuth
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.NodeKeyPair
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.Invite
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.byPreference
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import kotlinx.coroutines.flow.Flow

data class HouseholdSession(
    val household: Household,
    val self: LocalNode,
    val directory: Directory,
    val bootstrapSeeds: List<NodeEndpoint> = emptyList(),
    val keys: NodeKeyPair? = null,
) {
    /** Credentials for calls to other nodes: the secret admits us, the signature proves who we are. */
    val auth: ControlAuth get() = ControlAuth(household.secret, self.id, keys)

    /** This phone's role; everyone is a camera until an administrator raises it. */
    val role: AccessRole get() = directory.roles.roleOf(self.id, keys?.publicKey, household.ownerKey)

    fun roleOf(member: Member): AccessRole = directory.roles.roleOf(member.id, member.publicKey, household.ownerKey)

    fun can(permission: Permission): Boolean = role.can(permission)

    /** Seeds are the cameras: they are the members that keep a control server running. */
    fun invite(): Invite = Invite(household, directory.cameras.flatMap(Member::endpoints).byPreference())
}

interface HouseholdRepository {
    /** Null until this phone creates or joins a household. */
    val session: Flow<HouseholdSession?>

    suspend fun currentSession(): HouseholdSession?

    suspend fun create(householdName: String, deviceName: String): HouseholdSession

    /**
     * Joining another household starts from an empty directory; rejoining keeps it.
     * The invite's seeds are retried by every sync until one of them answers.
     */
    suspend fun join(invite: Invite, deviceName: String): HouseholdSession

    suspend fun leave()

    /** Merges a peer's view into ours and returns the merged result. Ends bootstrapping. */
    suspend fun mergeDirectory(remote: Directory): Directory

    /**
     * Publishes this phone's addresses, and its role when [role] is given (null keeps
     * the current one). Peers learn the change on their next sync.
     */
    suspend fun announceSelf(endpoints: List<NodeEndpoint>, role: NodeRole? = null)

    suspend fun removeMember(id: NodeId)

    /**
     * Signs a role for [id] with this phone's key. Returns false when this phone lacks the
     * authority (the verified merge drops the grant) or the device has not announced its key.
     */
    suspend fun assignRole(id: NodeId, role: AccessRole): Boolean

    suspend fun renameMember(id: NodeId, name: String)
}
