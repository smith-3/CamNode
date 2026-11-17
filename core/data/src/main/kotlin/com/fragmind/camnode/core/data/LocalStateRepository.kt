package com.fragmind.camnode.core.data

import androidx.datastore.core.DataStore
import com.fragmind.camnode.core.data.store.StoredState
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.data.device.DeviceClass
import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.AppLockState
import com.fragmind.camnode.core.model.PinCheck
import com.fragmind.camnode.core.model.PinHash
import com.fragmind.camnode.core.model.PinThrottle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.NodeKeys
import com.fragmind.camnode.core.model.RoleBook
import com.fragmind.camnode.core.model.RoleGrant
import com.fragmind.camnode.core.model.nextDisplayRotation
import com.fragmind.camnode.core.model.StreamProfile
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.HouseholdId
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.Invite
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LocalStateRepository @Inject constructor(
    private val store: DataStore<StoredState>,
    private val clock: WallClock,
    private val deviceClass: DeviceClass,
) : HouseholdRepository, CameraSettingsRepository, AlertsRepository, AppLockRepository {

    override val session: Flow<HouseholdSession?> = store.data.map { it.toSession() }.distinctUntilChanged()

    override val settings: Flow<CameraSettings> = store.data.map { it.cameraSettings ?: defaultCameraSettings() }.distinctUntilChanged()

    override val liveGrid: Flow<Boolean> = store.data.map { it.liveGrid ?: !deviceClass.isLowEnd }.distinctUntilChanged()

    override suspend fun setLiveGrid(enabled: Boolean) {
        store.updateData { it.copy(liveGrid = enabled) }
    }

    override val cameraLayout: Flow<CameraLayout> = store.data.map { it.cameraLayout }.distinctUntilChanged()

    override val displayRotations: Flow<Map<NodeId, Int>> = store.data
        .map { state -> state.displayRotations.mapKeys { (id, _) -> NodeId(id) } }
        .distinctUntilChanged()

    override suspend fun rotateDisplay(cameraId: NodeId) {
        store.updateData { state ->
            val next = nextDisplayRotation(state.displayRotations[cameraId.value] ?: 0)
            val rotations = if (next == 0) state.displayRotations - cameraId.value else state.displayRotations + (cameraId.value to next)
            state.copy(displayRotations = rotations)
        }
    }

    override suspend fun setCameraLayout(layout: CameraLayout) {
        store.updateData { it.copy(cameraLayout = layout) }
    }

    override val config: Flow<AppLockConfig> = store.data
        .map { AppLockConfig(it.appLock.enabled, it.appLock.disguise, it.appLock.iconStyle) }
        .distinctUntilChanged()

    override suspend fun enable(pin: String, disguise: AppDisguise, iconStyle: Int): Boolean {
        if (!disguise.accepts(pin) || iconStyle !in 0 until disguise.iconStyles) return false
        val salt = PinHash.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHash.hash(pin, salt) }
        store.updateData { it.copy(appLock = AppLockState(pinHash = hash, salt = salt, disguise = disguise, iconStyle = iconStyle)) }
        return true
    }

    override suspend fun changeDisguise(pin: String, disguise: AppDisguise, iconStyle: Int): PinCheck {
        if (!disguise.accepts(pin) || iconStyle !in 0 until disguise.iconStyles) return PinCheck.Denied(PinThrottle.ATTEMPTS_PER_ROUND)
        val result = verify(pin)
        if (result == PinCheck.Granted) store.updateData { it.copy(appLock = it.appLock.copy(disguise = disguise, iconStyle = iconStyle)) }
        return result
    }

    override suspend fun disable(pin: String): PinCheck {
        val result = verify(pin)
        if (result == PinCheck.Granted) store.updateData { it.copy(appLock = AppLockState()) }
        return result
    }

    /** Checks and records the attempt in one update, so concurrent tries can't skip the throttle. */
    override suspend fun verify(pin: String): PinCheck = withContext(Dispatchers.Default) {
        var result: PinCheck = PinCheck.Granted
        store.updateData { state ->
            val (check, next) = PinThrottle.check(state.appLock, pin, clock.nowMillis())
            result = check
            state.copy(appLock = next)
        }
        result
    }

    override val cameraShouldRun: Flow<Boolean> = store.data.map { it.cameraShouldRun }.distinctUntilChanged()

    override suspend fun setCameraShouldRun(shouldRun: Boolean) {
        store.updateData { it.copy(cameraShouldRun = shouldRun) }
    }

    override val receiverEnabled: Flow<Boolean> = store.data.map { it.wantsAlerts }.distinctUntilChanged()

    override val alertsMuted: Flow<Boolean> = store.data.map { it.alertsMuted }.distinctUntilChanged()

    override val cameraMutes: Flow<Map<NodeId, Long>> = store.data
        .map { state -> state.cameraMutes.mapKeys { (id, _) -> NodeId(id) } }
        .distinctUntilChanged()

    override suspend fun setCameraMuted(cameraId: NodeId, untilMillis: Long?) {
        store.updateData { state ->
            val mutes = if (untilMillis == null) state.cameraMutes - cameraId.value
            else state.cameraMutes + (cameraId.value to untilMillis)
            state.copy(cameraMutes = mutes)
        }
    }

    override val alerts: Flow<List<CameraEvent>> = store.data.map { it.alerts }.distinctUntilChanged()

    override suspend fun currentSession(): HouseholdSession? = session.first()

    /** The creator becomes the household owner: its key is pinned as the root of every role. */
    override suspend fun create(householdName: String, deviceName: String): HouseholdSession {
        val keys = store.updateData { it.withKeys() }.nodeKeys!!
        val household = Household(HouseholdId.random(), householdName.trim(), HouseholdSecret.generate(), ownerKey = keys.publicKey)
        return join(Invite(household, seeds = emptyList()), deviceName)
    }

    override suspend fun join(invite: Invite, deviceName: String): HouseholdSession {
        val household = invite.household
        val updated = store.updateData { current ->
            val state = current.withKeys()
            val self = LocalNode(state.self?.id ?: NodeId.random(), deviceName.trim(), state.nodeKeys?.publicKey)
            val directory = if (state.household?.id == household.id) state.directory else Directory()
            val previous = directory[self.id]
            val joined = state.copy(
                self = self, household = household, directory = directory, bootstrapSeeds = invite.seeds,
                cameraMutes = if (state.household?.id == household.id) state.cameraMutes else emptyMap(),
            )
            val me = Member(
                id = self.id,
                name = self.name,
                role = previous?.role ?: NodeRole.VIEWER,
                endpoints = previous?.endpoints.orEmpty(),
                updatedAtMillis = nextTimestamp(previous),
                receivesAlerts = joined.wantsAlerts,
                publicKey = self.publicKey,
            )
            joined.copy(directory = directory.upsert(me))
        }
        return checkNotNull(updated.toSession())
    }

    override suspend fun leave() {
        store.updateData { it.copy(household = null, directory = Directory(), bootstrapSeeds = emptyList(), cameraMutes = emptyMap()) }
    }

    override suspend fun mergeDirectory(remote: Directory): Directory =
        store.updateData { state ->
            val directory = state.directory.merge(remote, state.household?.ownerKey)
            val self = state.self?.let { local ->
                directory[local.id]?.takeUnless { it.removed }?.let { local.copy(name = it.name) } ?: local
            }
            val merged = state.copy(self = self, directory = directory, bootstrapSeeds = emptyList())
            // A role granted or revoked by an administrator changes whether this phone takes alerts.
            // A removed phone keeps its tombstone: rewriting the record would bring it back.
            val active = self?.let { directory[it.id] }?.removed == false
            if (active) merged.withSelf() else merged
        }.directory

    override suspend fun renameMember(id: NodeId, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name must not be blank" }
        store.updateData { state ->
            val member = state.directory[id]?.takeUnless { it.removed } ?: return@updateData state
            if (member.name == trimmed) return@updateData state
            state.copy(
                self = state.self?.let { if (it.id == id) it.copy(name = trimmed) else it },
                directory = state.directory.upsert(member.copy(name = trimmed, updatedAtMillis = nextTimestamp(member))),
            )
        }
    }

    override suspend fun announceSelf(endpoints: List<NodeEndpoint>, role: NodeRole?) {
        store.updateData { state -> state.withSelf(endpoints = endpoints, role = role) }
    }

    override suspend fun setAlertsMuted(muted: Boolean) {
        store.updateData { state -> state.copy(alertsMuted = muted).withSelf() }
    }

    override suspend fun record(event: CameraEvent): Boolean = recordReceipt(event, notification = false)

    override suspend fun recordForNotification(event: CameraEvent): Boolean = recordReceipt(event, notification = true)

    private suspend fun recordReceipt(event: CameraEvent, notification: Boolean): Boolean {
        var accepted = false
        store.updateData { state ->
            val isNew = event.id !in state.dismissedAlertIds && state.alerts.none { it.id == event.id }
            accepted = isNew && (!notification || (state.wantsAlerts &&
                (state.cameraMutes[event.cameraId.value] ?: 0L) <= clock.nowMillis()))
            if (!isNew) state else state.copy(alerts = (listOf(event) + state.alerts).take(MAX_ALERTS))
        }
        return accepted
    }

    override suspend fun dismiss(id: String) {
        store.updateData { state ->
            if (state.alerts.none { it.id == id }) state
            else state.copy(
                alerts = state.alerts.filterNot { it.id == id },
                dismissedAlertIds = (listOf(id) + state.dismissedAlertIds).distinct().take(MAX_DISMISSED_ALERTS),
            )
        }
    }

    override suspend fun restore(event: CameraEvent) {
        store.updateData { state ->
            if (event.id !in state.dismissedAlertIds) state
            else state.copy(
                alerts = (state.alerts + event).sortedByDescending { it.atMillis }.take(MAX_ALERTS),
                dismissedAlertIds = state.dismissedAlertIds.filterNot { it == event.id },
            )
        }
    }

    override suspend fun clear() {
        store.updateData { state ->
            state.copy(
                alerts = emptyList(),
                dismissedAlertIds = (state.alerts.map { it.id } + state.dismissedAlertIds).distinct().take(MAX_DISMISSED_ALERTS),
            )
        }
    }

    /** Rewrites this phone's directory record only when something peers care about changed. */
    private fun StoredState.withSelf(endpoints: List<NodeEndpoint>? = null, role: NodeRole? = null): StoredState {
        val self = self ?: return this
        val previous = directory[self.id]
        val next = Member(
            id = self.id,
            name = self.name,
            role = role ?: previous?.role ?: NodeRole.VIEWER,
            endpoints = endpoints ?: previous?.endpoints.orEmpty(),
            updatedAtMillis = 0,
            receivesAlerts = wantsAlerts,
            publicKey = nodeKeys?.publicKey ?: previous?.publicKey,
        )
        if (previous != null && previous.copy(updatedAtMillis = 0) == next) return this
        return copy(directory = directory.upsert(next.copy(updatedAtMillis = nextTimestamp(previous))))
    }

    /** In a secured household removal is also a signed revocation, so the device loses access, not just visibility. */
    override suspend fun removeMember(id: NodeId) {
        if (currentSession()?.household?.secured == true) assignRole(id, AccessRole.REVOKED)
        store.updateData { state ->
            val member = state.directory[id] ?: return@updateData state
            val tombstone = member.copy(removed = true, updatedAtMillis = nextTimestamp(member))
            state.copy(directory = state.directory.upsert(tombstone))
        }
    }

    override suspend fun assignRole(id: NodeId, role: AccessRole): Boolean {
        var granted = false
        store.updateData { state ->
            val keys = state.nodeKeys ?: return@updateData state
            val ownerKey = state.household?.ownerKey ?: return@updateData state
            val target = state.directory[id]?.publicKey ?: return@updateData state
            val grant = RoleGrant(id, target, role, clock.nowMillis(), keys.publicKey).signedBy(keys)
            val roles = state.directory.roles.merge(RoleBook(listOf(grant)), ownerKey)
            granted = grant in roles.grants
            if (granted) state.copy(directory = state.directory.withRoles(roles)) else state
        }
        return granted
    }

    /** Viewing includes alerts: administrators, viewers and recorders get them unless muted here. */
    private val StoredState.wantsAlerts: Boolean
        get() {
            val household = household ?: return false
            val self = self ?: return false
            return !alertsMuted && directory.roles.roleOf(self.id, nodeKeys?.publicKey, household.ownerKey).can(Permission.VIEW_STREAMS)
        }

    private fun StoredState.withKeys(): StoredState = if (nodeKeys != null) this else copy(nodeKeys = NodeKeys.generate())

    override suspend fun update(transform: (CameraSettings) -> CameraSettings) {
        store.updateData { it.copy(cameraSettings = transform(it.cameraSettings ?: defaultCameraSettings())) }
    }

    /** Never goes backwards, so a phone with a skewed clock still wins over its own older record. */
    private fun nextTimestamp(previous: Member?): Long =
        maxOf(clock.nowMillis(), (previous?.updatedAtMillis ?: 0L) + 1)

    /** 360p on old phones keeps the encoder, CPU and battery cool; 720p elsewhere. */
    private fun defaultCameraSettings() =
        CameraSettings(profile = if (deviceClass.isLowEnd) StreamProfile.LOW else StreamProfile.MEDIUM)

    private companion object {
        const val MAX_ALERTS = 50
        const val MAX_DISMISSED_ALERTS = 256
    }

    private fun StoredState.toSession(): HouseholdSession? {
        val household = household ?: return null
        val self = self ?: return null
        return HouseholdSession(household, self, directory, bootstrapSeeds, nodeKeys)
    }
}
