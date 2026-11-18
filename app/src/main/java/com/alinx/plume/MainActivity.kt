package com.alinx.plume

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.ui.editor.EditorScreen
import com.alinx.plume.ui.library.LibraryScreen
import com.alinx.plume.ui.theme.PlumeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = NoteRepository.get(this)
        setContent {
            PlumeTheme { PlumeApp(repo) }
        }
    }
}

@Composable
fun PlumeApp(repo: NoteRepository) {
    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    val noteId = openNoteId
    if (noteId == null) {
        LibraryScreen(repo, onOpenNote = { openNoteId = it })
    } else {
        BackHandler { openNoteId = null }
        EditorScreen(repo, noteId, onClose = { openNoteId = null })
    }
}
