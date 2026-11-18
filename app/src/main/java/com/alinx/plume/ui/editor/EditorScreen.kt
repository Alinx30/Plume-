package com.alinx.plume.ui.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.render.PageRenderer
import com.alinx.plume.ui.ConfirmDialog
import com.alinx.plume.ui.TextInputDialog
import com.alinx.plume.ui.theme.Desk
import com.alinx.plume.ui.theme.DeskDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(repo: NoteRepository, noteId: String, onClose: () -> Unit) {
    val index by repo.index.collectAsState()
    val meta = index.notes.firstOrNull { it.id == noteId }
    var state by remember(noteId) { mutableStateOf<EditorState?>(null) }

    LaunchedEffect(noteId) {
        state = EditorState(repo.loadContent(noteId))
    }
    LaunchedEffect(meta == null) {
        if (meta == null) onClose()
    }

    val s = state
    if (meta == null || s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    EditorContent(repo, noteId, meta.title, meta.paper, s, onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    repo: NoteRepository,
    noteId: String,
    title: String,
    paper: PaperStyle,
    state: EditorState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedVersion by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var paperMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }

    fun saveNow() {
        if (state.version != savedVersion) {
            repo.saveContent(noteId, state.toContent())
            savedVersion = state.version
        }
    }

    // Sauvegarde automatique, peu après chaque modification.
    LaunchedEffect(state.version) {
        delay(700)
        saveNow()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) saveNow() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            saveNow()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val content = state.toContent()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            PageRenderer.exportPdf(content, paper, it)
                        } ?: error("flux indisponible")
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (ok) "PDF exporté" else "L'export PDF a échoué",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
            title = {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { renaming = true },
                )
            },
            actions = {
                IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler")
                }
                IconButton(onClick = { state.redo() }, enabled = state.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rétablir")
                }
                IconButton(onClick = {
                    saveNow()
                    exportLauncher.launch(title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
                }) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = "Exporter en PDF")
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Plus")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Stylet uniquement (le doigt fait défiler)") },
                            trailingIcon = { Switch(checked = state.stylusOnly, onCheckedChange = null) },
                            onClick = { state.stylusOnly = !state.stylusOnly },
                        )
                        DropdownMenuItem(text = { Text("Type de papier…") }, onClick = { menu = false; paperMenu = true })
                        DropdownMenuItem(text = { Text("Renommer la note") }, onClick = { menu = false; renaming = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Effacer la page") }, onClick = { menu = false; state.clearPage() })
                        DropdownMenuItem(text = { Text("Supprimer la page") }, onClick = { menu = false; confirmDeletePage = true })
                    }
                    DropdownMenu(expanded = paperMenu, onDismissRequest = { paperMenu = false }) {
                        listOf(
                            PaperStyle.LINED to "Lignes",
                            PaperStyle.GRID to "Quadrillage",
                            PaperStyle.DOTS to "Points",
                            PaperStyle.BLANK to "Blanc",
                        ).forEach { (style, label) ->
                            DropdownMenuItem(
                                text = { Text(if (style == paper) "✓  $label" else "     $label") },
                                onClick = { paperMenu = false; repo.setPaper(noteId, style) },
                            )
                        }
                    }
                }
            },
        )
        ToolBar(state)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (MaterialTheme.colorScheme.background.luminanceIsDark()) DeskDark else Desk)
        ) {
            PageCanvas(state, paper)
        }
        PageBar(state)
    }

    if (renaming) {
        TextInputDialog(
            title = "Renommer la note", initial = title,
            onConfirm = { repo.renameNote(noteId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDeletePage) {
        ConfirmDialog(
            title = "Supprimer cette page ?",
            message = "La page ${state.pageIndex + 1} et son contenu seront supprimés.",
            confirmLabel = "Supprimer",
            onConfirm = { state.deletePage(); confirmDeletePage = false },
            onDismiss = { confirmDeletePage = false },
        )
    }
}

private fun Color.luminanceIsDark() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

@Composable
private fun ToolBar(state: EditorState) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledIconToggleButton(checked = state.tool == Tool.PEN, onCheckedChange = { state.tool = Tool.PEN }) {
                Icon(Icons.Filled.Edit, contentDescription = "Stylo")
            }
            FilledIconToggleButton(checked = state.tool == Tool.HIGHLIGHTER, onCheckedChange = { state.tool = Tool.HIGHLIGHTER }) {
                Icon(Icons.Filled.BorderColor, contentDescription = "Surligneur")
            }
            FilledIconToggleButton(checked = state.tool == Tool.ERASER, onCheckedChange = { state.tool = Tool.ERASER }) {
                Icon(EraserIcon, contentDescription = "Gomme")
            }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
            when (state.tool) {
                Tool.PEN -> {
                    PenColors.forEach { c ->
                        Swatch(c, selected = state.penColor == c) { state.penColor = c }
                    }
                    VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
                    PenWidths.forEach { w ->
                        WidthDot(w, selected = state.penWidth == w) { state.penWidth = w }
                    }
                }
                Tool.HIGHLIGHTER -> HighlighterColors.forEach { c ->
                    Swatch(c, selected = state.highlighterColor == c, alpha = 0.6f) { state.highlighterColor = c }
                }
                Tool.ERASER -> Text(
                    "Touchez un trait pour l'effacer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Long, selected: Boolean, alpha: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(color).copy(alpha = alpha))
            .clickable(onClick = onClick)
    )
}

@Composable
private fun WidthDot(width: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((width * 2f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
private fun PageBar(state: EditorState) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { state.goTo(state.pageIndex - 1) }, enabled = state.pageIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Page précédente")
            }
            Text("Page ${state.pageIndex + 1} / ${state.pages.size}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { state.goTo(state.pageIndex + 1) }, enabled = state.pageIndex < state.pages.size - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Page suivante")
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { state.addPage() }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Page")
            }
        }
    }
}

private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.24f, 3.56f)
            lineTo(21.19f, 8.5f)
            curveTo(21.97f, 9.29f, 21.97f, 10.55f, 21.19f, 11.34f)
            lineTo(12f, 20.53f)
            curveTo(10.44f, 22.09f, 7.91f, 22.09f, 6.34f, 20.53f)
            lineTo(2.81f, 17f)
            curveTo(2.03f, 16.21f, 2.03f, 14.95f, 2.81f, 14.16f)
            lineTo(13.41f, 3.56f)
            curveTo(14.2f, 2.78f, 15.46f, 2.78f, 16.24f, 3.56f)
            close()
            moveTo(4.22f, 15.58f)
            lineTo(7.76f, 19.11f)
            curveTo(8.54f, 19.9f, 9.8f, 19.9f, 10.59f, 19.11f)
            lineTo(14.12f, 15.58f)
            lineTo(9.17f, 10.63f)
            lineTo(4.22f, 15.58f)
            close()
        }
    }.build()
}
