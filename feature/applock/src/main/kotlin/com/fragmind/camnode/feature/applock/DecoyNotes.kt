package com.fragmind.camnode.feature.applock

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DecoyNote(val id: String, val text: String, val editedAtMillis: Long)

@Serializable
private data class DecoyNotesFile(val notes: List<DecoyNote> = emptyList())

private object DecoyNotesSerializer : Serializer<DecoyNotesFile> {
    private val json = Json { ignoreUnknownKeys = true }
    override val defaultValue = DecoyNotesFile()

    override suspend fun readFrom(input: InputStream): DecoyNotesFile = try {
        json.decodeFromString(DecoyNotesFile.serializer(), input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("Unreadable notes", error)
    }

    override suspend fun writeTo(t: DecoyNotesFile, output: OutputStream) {
        output.write(json.encodeToString(DecoyNotesFile.serializer(), t).encodeToByteArray())
    }
}

/** The notes disguise is a real notes app; its notes live apart from CamNode's own state. */
@Singleton
class DecoyNotesStore @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<DecoyNotesFile> = DataStoreFactory.create(
        serializer = DecoyNotesSerializer,
        produceFile = { context.dataStoreFile("decoy_notes.json") },
    )

    val notes: Flow<List<DecoyNote>> = store.data.map { file -> file.notes.sortedByDescending { it.editedAtMillis } }

    /** Saves [text] as a new note when [id] is null; a blank text deletes the note. */
    suspend fun save(id: String?, text: String) {
        store.updateData { file ->
            val others = file.notes.filterNot { it.id == id }
            if (text.isBlank()) file.copy(notes = others)
            else file.copy(notes = others + DecoyNote(id ?: UUID.randomUUID().toString(), text, System.currentTimeMillis()))
        }
    }

    suspend fun delete(id: String) = save(id, "")
}
