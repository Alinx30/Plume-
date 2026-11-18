package com.alinx.plume.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.NoteMeta
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.data.Notebook
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.render.PageRenderer
import com.alinx.plume.ui.ConfirmDialog
import com.alinx.plume.ui.TextInputDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface LibDialog {
    data object NewNotebook : LibDialog
    data class RenameNotebook(val nb: Notebook) : LibDialog
    data class DeleteNotebook(val nb: Notebook) : LibDialog
    data class RenameNote(val note: NoteMeta) : LibDialog
    data class DeleteNote(val note: NoteMeta) : LibDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(repo: NoteRepository, onOpenNote: (String) -> Unit) {
    val index by repo.index.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = index.notebooks.firstOrNull { it.id == selectedId } ?: index.notebooks.first()
    val notes = index.notes.filter { it.notebookId == selected.id }.sortedByDescending { it.updatedAt }
    var dialog by remember { mutableStateOf<LibDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plume", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { dialog = LibDialog.NewNotebook }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau carnet")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options du carnet")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Renommer « ${selected.name} »") },
                                onClick = { menuOpen = false; dialog = LibDialog.RenameNotebook(selected) },
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer « ${selected.name} »") },
                                onClick = { menuOpen = false; dialog = LibDialog.DeleteNotebook(selected) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenNote(repo.createNote(selected.id).id) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nouvelle note") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(index.notebooks, key = { it.id }) { nb ->
                    FilterChip(
                        selected = nb.id == selected.id,
                        onClick = { selectedId = nb.id },
                        label = { Text(nb.name) },
                        leadingIcon = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(nb.color)))
                        },
                    )
                }
            }
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucune note dans « ${selected.name} ».\nTouchez « Nouvelle note » pour commencer à écrire.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            repo = repo,
                            note = note,
                            notebooks = index.notebooks,
                            onOpen = { onOpenNote(note.id) },
                            onRename = { dialog = LibDialog.RenameNote(note) },
                            onDelete = { dialog = LibDialog.DeleteNote(note) },
                            onMove = { repo.moveNote(note.id, it) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        LibDialog.NewNotebook -> TextInputDialog(
            title = "Nouveau carnet", initial = "", confirmLabel = "Créer",
            onConfirm = { selectedId = repo.createNotebook(it).id; dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.RenameNotebook -> TextInputDialog(
            title = "Renommer le carnet", initial = d.nb.name,
            onConfirm = { repo.renameNotebook(d.nb.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.DeleteNotebook -> ConfirmDialog(
            title = "Supprimer le carnet ?",
            message = "« ${d.nb.name} » et toutes ses notes seront supprimés définitivement.",
            confirmLabel = "Supprimer",
            onConfirm = { repo.deleteNotebook(d.nb.id); selectedId = null; dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.RenameNote -> TextInputDialog(
            title = "Renommer la note", initial = d.note.title,
            onConfirm = { repo.renameNote(d.note.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.DeleteNote -> ConfirmDialog(
            title = "Supprimer la note ?",
            message = "« ${d.note.title} » sera supprimée définitivement.",
            confirmLabel = "Supprimer",
            onConfirm = { repo.deleteNote(d.note.id); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    repo: NoteRepository,
    note: NoteMeta,
    notebooks: List<Notebook>,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val content by produceState<NoteContent?>(null, note.id, note.updatedAt) {
        value = repo.loadContent(note.id)
    }
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Card(
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.414f).shadow(3.dp, RoundedCornerShape(6.dp)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = content ?: return@Canvas
                val page = c.pages.firstOrNull() ?: return@Canvas
                val s = size.width / PAGE_W
                drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    nc.save()
                    nc.scale(s, s)
                    PageRenderer.drawPage(nc, page.strokes, note.paper, null)
                    nc.restore()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDate(note.updatedAt) + " · " + pagesLabel(note.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options de la note")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ouvrir") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                    if (notebooks.size > 1) {
                        DropdownMenuItem(text = { Text("Déplacer vers…") }, onClick = { menu = false; moveMenu = true })
                    }
                    DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                }
                DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                    notebooks.filter { it.id != note.notebookId }.forEach { nb ->
                        DropdownMenuItem(text = { Text(nb.name) }, onClick = { moveMenu = false; onMove(nb.id) })
                    }
                }
            }
        }
    }
}

private fun pagesLabel(n: Int) = if (n <= 1) "1 page" else "$n pages"

private fun formatDate(t: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(t))
