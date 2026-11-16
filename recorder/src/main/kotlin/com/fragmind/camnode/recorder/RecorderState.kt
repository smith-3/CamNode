package com.fragmind.camnode.recorder

import com.fragmind.camnode.core.control.ControlAuth
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.NodeKeyPair
import com.fragmind.camnode.core.model.NodeKeys
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class RecorderState(
    val household: Household,
    val self: LocalNode,
    val storageDir: String,
    val retentionDays: Int = 7,
    val directory: Directory = Directory(),
    val bootstrapSeeds: List<NodeEndpoint> = emptyList(),
    /** This computer's own identity; an administrator grants it the recorder role. */
    val keys: NodeKeyPair = NodeKeys.generate(),
) {
    val auth: ControlAuth get() = ControlAuth(household.secret, self.id, keys)

    val role: AccessRole get() = directory.roles.roleOf(self.id, keys.publicKey, household.ownerKey)
}

/** The recorder's only persistent state: one JSON file, written atomically. */
class RecorderStore(private val file: Path) {
    private val lock = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun exists(): Boolean = Files.exists(file)

    suspend fun read(): RecorderState = lock.withLock { load() }

    suspend fun update(transform: (RecorderState) -> RecorderState): RecorderState = lock.withLock {
        transform(load()).also(::save)
    }

    fun create(state: RecorderState) {
        Files.createDirectories(file.parent)
        save(state)
    }

    private fun load(): RecorderState = json.decodeFromString(RecorderState.serializer(), Files.readString(file))

    private fun save(state: RecorderState) {
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(RecorderState.serializer(), state))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Publishes the recorder in the directory; the timestamp never goes backwards for its own record. */
fun RecorderState.announced(endpoints: List<NodeEndpoint>, nowMillis: Long): RecorderState {
    val previous = directory[self.id]
    val next = Member(
        id = self.id,
        name = previous?.name ?: self.name,
        role = NodeRole.RECORDER,
        endpoints = endpoints,
        updatedAtMillis = 0,
        receivesAlerts = true,
        publicKey = keys.publicKey,
    )
    if (previous != null && previous.copy(updatedAtMillis = 0) == next) return this
    val timestamp = maxOf(nowMillis, (previous?.updatedAtMillis ?: 0L) + 1)
    return copy(directory = directory.upsert(next.copy(updatedAtMillis = timestamp)))
}
