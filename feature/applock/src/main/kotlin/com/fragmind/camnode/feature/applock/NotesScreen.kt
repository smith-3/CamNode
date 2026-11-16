package com.fragmind.camnode.feature.applock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.AppPin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
internal class DecoyNotesViewModel @Inject constructor(private val store: DecoyNotesStore) : ViewModel() {
    val notes: StateFlow<List<DecoyNote>> = store.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(id: String?, text: String) {
        viewModelScope.launch { store.save(id, text) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }
}

/** The editor's target: a new note has no id yet. */
private const val NEW_NOTE = ""

/** A working notes app. Saving a note whose whole text is the PIN opens CamNode; that note is not kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesDisguise(onPinCandidate: (String, onDenied: () -> Unit) -> Unit, viewModel: DecoyNotesViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    val editingId = editing
    if (editingId != null) {
        fun close() {
            val id = editingId.ifEmpty { null }
            val text = draft
            editing = null
            val candidate = text.trim()
            if (AppPin.isWellFormed(candidate)) onPinCandidate(candidate) { viewModel.save(id, text) } else viewModel.save(id, text)
        }
        BackHandler(onBack = ::close)
        NoteEditor(
            text = draft,
            onTextChange = { draft = it },
            onDone = ::close,
            onDelete = { editingId.ifEmpty { null }?.let(viewModel::delete); editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.disguise_notes), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { draft = ""; editing = NEW_NOTE }) { Icon(CamNodeIcons.Add, contentDescription = stringResource(R.string.applock_notes_new)) }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.applock_notes_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(notes, key = DecoyNote::id) { note ->
                CamNodeCard(onClick = { draft = note.text; editing = note.id }, contentPadding = PaddingValues(16.dp)) {
                    Text(note.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(note.editedAtMillis.asNoteDate(LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(text: String, onTextChange: (String) -> Unit, onDone: () -> Unit, onDelete: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onDone) { Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.applock_notes_save_back)) } },
                actions = {
                    IconButton(onClick = onDelete) { Icon(CamNodeIcons.Delete, contentDescription = stringResource(R.string.applock_notes_delete)) }
                    TextButton(onClick = onDone) { Text(stringResource(R.string.applock_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.padding(padding).fillMaxSize(),
            placeholder = { Text(stringResource(R.string.applock_notes_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

private fun Long.asNoteDate(locale: Locale): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(bestPattern(locale, "dMMMjjmm"))
