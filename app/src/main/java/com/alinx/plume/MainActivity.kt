package com.alinx.plume

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.ui.editor.EditorScreen
import com.alinx.plume.ui.library.LibraryScreen
import com.alinx.plume.ui.theme.PlumeTheme

class MainActivity : ComponentActivity() {
    private val pendingPdf: MutableState<Uri?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = NoteRepository.get(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PlumeTheme { PlumeApp(repo, pendingPdf) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) pendingPdf.value = uri
    }
}

@Composable
fun PlumeApp(repo: NoteRepository, pendingPdf: MutableState<Uri?>) {
    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // PDF reçu d'une autre appli : on l'importe et on l'ouvre.
    val incoming = pendingPdf.value
    LaunchedEffect(incoming) {
        if (incoming != null) {
            pendingPdf.value = null
            val note = repo.importPdf(incoming, null)
            if (note != null) openNoteId = note.id
            else Toast.makeText(context, "Impossible d'ouvrir ce PDF", Toast.LENGTH_LONG).show()
        }
    }

    val noteId = openNoteId
    if (noteId == null) {
        LibraryScreen(repo, onOpenNote = { openNoteId = it })
    } else {
        BackHandler { openNoteId = null }
        EditorScreen(repo, noteId, onClose = { openNoteId = null })
    }
}
