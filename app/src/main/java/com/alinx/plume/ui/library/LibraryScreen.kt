package com.alinx.plume.ui.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alinx.plume.data.Folder
import com.alinx.plume.data.LibraryIndex
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.NoteMeta
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.render.Bitmaps
import com.alinx.plume.render.PageRenderer
import com.alinx.plume.ui.ConfirmDialog
import com.alinx.plume.ui.TextInputDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL = "all"
private const val RECENT = "recent"
private const val UNFILED = "unfiled"

private enum class SortBy(val label: String) { MODIFIED("Date de modification"), CREATED("Date de création"), TITLE("Titre") }

private sealed interface LibDialog {
    data class NewFolder(val parentId: String?) : LibDialog
    data class RenameFolder(val f: Folder) : LibDialog
    data class DeleteFolder(val f: Folder) : LibDialog
    data class RenameNote(val note: NoteMeta) : LibDialog
    data class DeleteNote(val note: NoteMeta) : LibDialog
    data class MoveNote(val note: NoteMeta) : LibDialog
}

@Composable
fun LibraryScreen(repo: NoteRepository, onOpenNote: (String) -> Unit) {
    val index by repo.index.collectAsState()
    var selected by rememberSaveable { mutableStateOf(ALL) }
    var dialog by remember { mutableStateOf<LibDialog?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Si le dossier sélectionné a été supprimé, on revient à « Toutes les notes ».
    if (selected != ALL && selected != RECENT && selected != UNFILED && index.folders.none { it.id == selected }) {
        selected = ALL
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val sidebar = @Composable {
            Sidebar(
                index = index,
                selected = selected,
                onSelect = { selected = it; scope.launch { drawer.close() } },
                onDialog = { dialog = it },
            )
        }
        val notes = @Composable {
            NotesPane(
                repo = repo,
                index = index,
                selected = selected,
                showMenuButton = !wide,
                onMenu = { scope.launch { drawer.open() } },
                onOpenNote = onOpenNote,
                onDialog = { dialog = it },
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.width(290.dp).fillMaxHeight()) {
                    sidebar()
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) { notes() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = { ModalDrawerSheet(Modifier.width(300.dp)) { sidebar() } },
            ) { notes() }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is LibDialog.NewFolder -> TextInputDialog(
            title = if (d.parentId == null) "Nouveau dossier" else "Nouveau sous-dossier",
            initial = "", confirmLabel = "Créer",
            onConfirm = { selected = repo.createFolder(it, d.parentId).id; dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.RenameFolder -> TextInputDialog(
            title = "Renommer le dossier", initial = d.f.name,
            onConfirm = { repo.renameFolder(d.f.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is LibDialog.DeleteFolder -> ConfirmDialog(
            title = "Supprimer le dossier ?",
            message = "« ${d.f.name} », ses sous-dossiers et toutes leurs notes seront supprimés définitivement.",
            confirmLabel = "Supprimer",
            onConfirm = { repo.deleteFolder(d.f.id); dialog = null },
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
        is LibDialog.MoveNote -> MoveDialog(
            index = index,
            current = d.note.folderId,
            onPick = { repo.moveNote(d.note.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

// ---------------- Barre latérale ----------------

@Composable
private fun Sidebar(
    index: LibraryIndex,
    selected: String,
    onSelect: (String) -> Unit,
    onDialog: (LibDialog) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Plume", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { onDialog(LibDialog.NewFolder(null)) }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau dossier")
            }
        }
        SidebarRow(Icons.Filled.Description, "Toutes les notes", index.notes.size, selected == ALL, depth = 0) { onSelect(ALL) }
        SidebarRow(Icons.Filled.Schedule, "Récentes", null, selected == RECENT, depth = 0) { onSelect(RECENT) }
        SidebarRow(Icons.Filled.Inbox, "Non classées", index.notes.count { it.folderId == null }, selected == UNFILED, depth = 0) { onSelect(UNFILED) }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Text(
            "Dossiers",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        if (index.folders.isEmpty()) {
            TextButton(onClick = { onDialog(LibDialog.NewFolder(null)) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Créer un dossier")
            }
        }
        fun tree(parent: String?, depth: Int): List<Pair<Folder, Int>> =
            index.folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }.flatMap { f ->
                listOf(f to depth) + if (f.id in expanded) tree(f.id, depth + 1) else emptyList()
            }
        tree(null, 0).forEach { (f, depth) ->
            val hasChildren = index.folders.any { it.parentId == f.id }
            FolderRow(
                folder = f,
                depth = depth,
                count = index.notes.count { it.folderId == f.id },
                selected = selected == f.id,
                hasChildren = hasChildren,
                expanded = f.id in expanded,
                onToggle = { expanded = if (f.id in expanded) expanded - f.id else expanded + f.id },
                onClick = { onSelect(f.id) },
                onNewSub = { expanded = expanded + f.id; onDialog(LibDialog.NewFolder(f.id)) },
                onRename = { onDialog(LibDialog.RenameFolder(f)) },
                onDelete = { onDialog(LibDialog.DeleteFolder(f)) },
            )
        }
    }
}

@Composable
private fun SidebarRow(icon: ImageVector, label: String, count: Int?, selected: Boolean, depth: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = (12 + depth * 18).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    depth: Int,
    count: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onNewSub: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = (depth * 18).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = hasChildren, onClick = onToggle), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Replier" else "Déplier",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(Icons.Filled.Folder, contentDescription = null, tint = Color(folder.color), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options du dossier", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Nouveau sous-dossier") }, onClick = { menu = false; onNewSub() })
                DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// ---------------- Liste des notes ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesPane(
    repo: NoteRepository,
    index: LibraryIndex,
    selected: String,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDialog: (LibDialog) -> Unit,
) {
    var sortBy by rememberSaveable { mutableStateOf(SortBy.MODIFIED) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val folder = index.folders.firstOrNull { it.id == selected }
    val title = when (selected) {
        ALL -> "Toutes les notes"
        RECENT -> "Récentes"
        UNFILED -> "Non classées"
        else -> folder?.name ?: "Notes"
    }
    val folderName = remember(index.folders) { index.folders.associate { it.id to it.name } }

    val notes = run {
        var list = when (selected) {
            ALL -> index.notes
            RECENT -> index.notes.sortedByDescending { it.updatedAt }.take(20)
            UNFILED -> index.notes.filter { it.folderId == null }
            else -> index.notes.filter { it.folderId == selected }
        }
        val q = query.trim()
        if (searching && q.isNotEmpty()) {
            // La recherche porte sur toute la bibliothèque.
            list = index.notes.filter {
                it.title.contains(q, ignoreCase = true) || (folderName[it.folderId]?.contains(q, ignoreCase = true) == true)
            }
        }
        if (selected == RECENT && !(searching && q.isNotEmpty())) list else when (sortBy) {
            SortBy.MODIFIED -> list.sortedByDescending { it.updatedAt }
            SortBy.CREATED -> list.sortedByDescending { it.createdAt }
            SortBy.TITLE -> list.sortedBy { it.title.lowercase() }
        }
    }
    val newNoteFolder = folder?.id
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val note = repo.importPdf(uri, newNoteFolder)
                importing = false
                if (note != null) onOpenNote(note.id)
                else Toast.makeText(context, "Impossible d'importer ce PDF", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (showMenuButton) IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Dossiers") }
                    },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "Rechercher")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Trier")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(if (s == sortBy) "✓  ${s.label}" else "     ${s.label}") },
                                        onClick = { sortBy = s; sortMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { grid = !grid }) {
                            Icon(
                                if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = if (grid) "Affichage en liste" else "Affichage en grille",
                            )
                        }
                    },
                )
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Rechercher une note ou un dossier") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Importer un PDF", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = { onOpenNote(repo.createNote(newNoteFolder).id) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Nouvelle note") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (importing) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (notes.isEmpty()) {
                Text(
                    if (searching && query.isNotBlank()) "Aucune note ne correspond à « ${query.trim()} »."
                    else "Aucune note ici.\nTouchez « Nouvelle note » pour commencer à écrire.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            repo, note, folderName[note.folderId],
                            onOpen = { onOpenNote(note.id) },
                            onAction = { a ->
                                when (a) {
                                    NoteAction.RENAME -> onDialog(LibDialog.RenameNote(note))
                                    NoteAction.MOVE -> onDialog(LibDialog.MoveNote(note))
                                    NoteAction.DUPLICATE -> scope.launch { repo.duplicateNote(note.id) }
                                    NoteAction.DELETE -> onDialog(LibDialog.DeleteNote(note))
                                }
                            },
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            repo, note, folderName[note.folderId],
                            onOpen = { onOpenNote(note.id) },
                            onAction = { a ->
                                when (a) {
                                    NoteAction.RENAME -> onDialog(LibDialog.RenameNote(note))
                                    NoteAction.MOVE -> onDialog(LibDialog.MoveNote(note))
                                    NoteAction.DUPLICATE -> scope.launch { repo.duplicateNote(note.id) }
                                    NoteAction.DELETE -> onDialog(LibDialog.DeleteNote(note))
                                }
                            },
                        )
                        HorizontalDivider(Modifier.padding(start = 96.dp))
                    }
                }
            }
        }
    }
}

private enum class NoteAction { RENAME, MOVE, DUPLICATE, DELETE }

@Composable
private fun NoteMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (NoteAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Renommer") }, onClick = { onDismiss(); onAction(NoteAction.RENAME) })
        DropdownMenuItem(text = { Text("Déplacer vers…") }, onClick = { onDismiss(); onAction(NoteAction.MOVE) })
        DropdownMenuItem(text = { Text("Dupliquer") }, onClick = { onDismiss(); onAction(NoteAction.DUPLICATE) })
        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { onDismiss(); onAction(NoteAction.DELETE) })
    }
}

@Composable
private fun Thumbnail(repo: NoteRepository, note: NoteMeta, modifier: Modifier) {
    val content by produceState<NoteContent?>(null, note.id, note.updatedAt) {
        value = repo.loadContent(note.id)
    }
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier.aspectRatio(1f / 1.414f).shadow(3.dp, RoundedCornerShape(6.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (Bitmaps.version.intValue < 0) return@Canvas
            val c = content ?: return@Canvas
            val page = c.pages.firstOrNull() ?: return@Canvas
            val s = size.width / PAGE_W
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                nc.save()
                nc.scale(s, s)
                PageRenderer.drawPage(nc, page, note.paperSpec, null, pixelWidth = size.width.toInt())
                nc.restore()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(repo: NoteRepository, note: NoteMeta, folderName: String?, onOpen: () -> Unit, onAction: (NoteAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Thumbnail(repo, note, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDate(note.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folderName != null) {
                    Text(folderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options de la note", modifier = Modifier.size(18.dp))
                }
                NoteMenu(menu, { menu = false }, onAction)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(repo: NoteRepository, note: NoteMeta, folderName: String?, onOpen: () -> Unit, onAction: (NoteAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(repo, note, Modifier.width(56.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDate(note.updatedAt) + " · " + pagesLabel(note.pageCount) + (folderName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options de la note") }
            NoteMenu(menu, { menu = false }, onAction)
        }
    }
}

@Composable
private fun MoveDialog(index: LibraryIndex, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    fun tree(parent: String?, depth: Int): List<Pair<Folder, Int>> =
        index.folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }.flatMap { f ->
            listOf(f to depth) + tree(f.id, depth + 1)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer vers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MoveRow(Icons.Filled.Inbox, "Non classées", 0, current == null, Color.Unspecified) { onPick(null) }
                tree(null, 0).forEach { (f, depth) ->
                    MoveRow(Icons.Filled.Folder, f.name, depth, current == f.id, Color(f.color)) { onPick(f.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun MoveRow(icon: ImageVector, label: String, depth: Int, isCurrent: Boolean, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(start = (8 + depth * 18).dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.primary else tint)
        Spacer(Modifier.width(12.dp))
        Text(
            if (isCurrent) "$label (actuel)" else label,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun pagesLabel(n: Int) = if (n <= 1) "1 page" else "$n pages"

private fun formatDate(t: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(t))

