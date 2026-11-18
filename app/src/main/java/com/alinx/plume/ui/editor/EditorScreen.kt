package com.alinx.plume.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.data.PaperSpec
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.ui.PaperPickerDialog
import com.alinx.plume.render.PageRenderer
import com.alinx.plume.ui.ConfirmDialog
import com.alinx.plume.ui.TextInputDialog
import com.alinx.plume.ui.theme.Desk
import com.alinx.plume.ui.theme.DeskDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    EditorContent(repo, noteId, meta.title, meta.paperSpec, s, onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    repo: NoteRepository,
    noteId: String,
    title: String,
    paper: PaperSpec,
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
        state.commitText()
        if (state.version != savedVersion) {
            repo.saveContent(noteId, state.toContent())
            savedVersion = state.version
        }
    }

    // Sauvegarde automatique, peu après chaque modification.
    LaunchedEffect(state.version) {
        delay(800)
        if (state.version != savedVersion) {
            repo.saveContent(noteId, state.toContent())
            savedVersion = state.version
        }
    }
    val audio = remember(state) { AudioController(context.applicationContext, state) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                audio.stopRecording()
                audio.pause()
                saveNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            audio.release()
            saveNow()
        }
    }
    LaunchedEffect(audio.isPlaying) {
        while (audio.isPlaying) { audio.tick(); delay(40) }
    }
    LaunchedEffect(audio.recording) {
        while (audio.recording) { audio.updateElapsed(); delay(250) }
    }
    LaunchedEffect(state.seekRequest) {
        val target = state.seekRequest ?: return@LaunchedEffect
        state.seekRequest = null
        audio.seek(target)
        if (!audio.isPlaying) audio.play()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Plume a besoin du micro pour enregistrer", Toast.LENGTH_LONG).show()
        } else if (!audio.startRecording()) {
            Toast.makeText(context, "Impossible de démarrer l'enregistrement", Toast.LENGTH_SHORT).show()
        }
    }
    fun toggleRecording() {
        if (audio.recording) {
            audio.stopRecording()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!audio.startRecording()) Toast.makeText(context, "Impossible de démarrer l'enregistrement", Toast.LENGTH_SHORT).show()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    var recordingsMenu by remember { mutableStateOf(false) }

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
                Toast.makeText(context, if (ok) "PDF exporté" else "L'export PDF a échoué", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val page = state.currentPage
            scope.launch {
                val img = repo.importImage(uri)
                if (img != null) state.insertImage(page, img.first, img.second, img.third)
                else Toast.makeText(context, "Impossible d'ajouter cette photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            navigationIcon = {
                IconButton(onClick = { saveNow(); onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Bibliothèque")
                }
            },
            title = {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { renaming = true },
                )
            },
            actions = {
                IconButton(onClick = { state.startTypingBelowContent(state.currentPage, textMargin(paper.style)) }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Écrire au clavier")
                }
                if (audio.recording) {
                    Surface(
                        onClick = { toggleRecording() },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFD93025),
                        contentColor = Color.White,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Stop, contentDescription = "Arrêter l'enregistrement", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(formatDuration(audio.recordElapsed), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    IconButton(onClick = { toggleRecording() }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Enregistrer l'audio")
                    }
                }
                if (state.recordings.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { recordingsMenu = true }) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = "Écouter les enregistrements")
                        }
                        DropdownMenu(expanded = recordingsMenu, onDismissRequest = { recordingsMenu = false }) {
                            state.recordings.forEachIndexed { i, rec ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Enregistrement ${i + 1}")
                                            Text(
                                                formatDate(rec.createdAt) + " · " + formatDuration(rec.durationMs),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                    onClick = { recordingsMenu = false; state.clearSelection(); state.commitText(); audio.open(rec) },
                                )
                            }
                        }
                    }
                }
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
                        DropdownMenuItem(text = { Text("Papier…") }, onClick = { menu = false; paperMenu = true })
                        DropdownMenuItem(text = { Text("Renommer la note") }, onClick = { menu = false; renaming = true })
                        HorizontalDivider()
                        val p = state.currentPage + 1
                        DropdownMenuItem(text = { Text("Insérer une page après la page $p") }, onClick = { menu = false; state.insertPageAfter(state.currentPage) })
                        DropdownMenuItem(text = { Text("Effacer la page $p") }, onClick = { menu = false; state.clearPage(state.currentPage) })
                        DropdownMenuItem(text = { Text("Supprimer la page $p") }, onClick = { menu = false; confirmDeletePage = true })
                    }
                }
            },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (MaterialTheme.colorScheme.surface.isDark()) DeskDark else Desk)
        ) {
            DocumentCanvas(state, paper)
            if (state.pages.none { it.hasContent() } && audio.playing == null && state.editing == null) {
                Surface(
                    onClick = { paperMenu = true },
                    shape = RoundedCornerShape(50),
                    shadowElevation = 6.dp,
                    tonalElevation = 3.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                ) {
                    Text(
                        "Papier : ${paper.style.label}  ·  Changer",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            val rec = audio.playing
            if (rec != null) {
                PlayerBar(
                    audio = audio,
                    title = "Enregistrement ${state.recordings.indexOfFirst { it.id == rec.id } + 1}",
                    onDelete = { audio.deleteRecording(rec) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            ToolPill(
                state,
                onInsertImage = {
                    imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 8.dp, end = 8.dp),
            )
        }
    }

    if (paperMenu) {
        PaperPickerDialog(
            initial = paper,
            onConfirm = { spec ->
                repo.setPaper(noteId, spec)
                repo.defaultPaper = spec
                paperMenu = false
            },
            onDismiss = { paperMenu = false },
        )
    }
    if (renaming) {
        TextInputDialog(
            title = "Renommer la note", initial = title,
            onConfirm = { repo.renameNote(noteId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDeletePage) {
        val p = state.currentPage
        ConfirmDialog(
            title = "Supprimer la page ${p + 1} ?",
            message = "La page et son contenu seront supprimés.",
            confirmLabel = "Supprimer",
            onConfirm = { state.deletePage(p); confirmDeletePage = false },
            onDismiss = { confirmDeletePage = false },
        )
    }
}

@Composable
private fun PlayerBar(audio: AudioController, title: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 10.dp,
        tonalElevation = 3.dp,
        modifier = modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 640.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (audio.isPlaying) audio.pause() else audio.play() }) {
                    Icon(
                        if (audio.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (audio.isPlaying) "Pause" else "Lecture",
                    )
                }
                Text(formatDuration(audio.position), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = if (audio.duration > 0) audio.position.toFloat() / audio.duration else 0f,
                    onValueChange = { audio.seek((it * audio.duration).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatDuration(audio.duration), style = MaterialTheme.typography.labelMedium)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Supprimer l'enregistrement") }, onClick = { menu = false; onDelete() })
                    }
                }
                IconButton(onClick = { audio.closePlayer() }) { Icon(Icons.Filled.Close, contentDescription = "Fermer") }
            }
            Text(
                "$title · touchez un trait pour réécouter ce moment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
    }
}

private fun formatDate(t: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.FRANCE).format(Date(t))

/** Marge gauche du texte tapé selon le papier (après la marge rouge s'il y en a une). */
private fun textMargin(style: PaperStyle): Float = when (style) {
    PaperStyle.LINED, PaperStyle.LINED_WIDE -> 130f
    PaperStyle.SEYES -> 210f
    PaperStyle.CORNELL -> 300f
    else -> 60f
}

private fun Color.isDark() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/** Barre d'outils flottante. Toucher l'outil déjà actif ouvre ses réglages. */
@Composable
private fun ToolPill(state: EditorState, onInsertImage: () -> Unit, modifier: Modifier = Modifier) {
    var optionsFor by remember { mutableStateOf<Tool?>(null) }

    fun tap(t: Tool) {
        if (state.tool == t) optionsFor = if (optionsFor == t) null else t
        else { state.selectTool(t); optionsFor = null }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 8.dp,
            tonalElevation = 3.dp,
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(state.tool == Tool.PEN, "Stylo", Icons.Filled.Create, Color(state.penColor)) { tap(Tool.PEN) }
                ToolButton(state.tool == Tool.HIGHLIGHTER, "Surligneur", Icons.Filled.BorderColor, Color(state.highlighterColor)) { tap(Tool.HIGHLIGHTER) }
                ToolButton(state.tool == Tool.ERASER, "Gomme", EraserIcon, null) { tap(Tool.ERASER) }
                ToolButton(state.tool == Tool.LASSO, "Lasso", LassoIcon, null) { tap(Tool.LASSO) }
                ToolButton(state.tool == Tool.TEXT, "Texte", Icons.Filled.TextFields, Color(state.textColor)) { tap(Tool.TEXT) }
                ToolButton(false, "Insérer une photo", Icons.Filled.AddPhotoAlternate, null) { optionsFor = null; onInsertImage() }
                VerticalDivider(Modifier.height(26.dp).padding(horizontal = 6.dp))
                val fingerLabel = if (state.fingerDraws) "Le doigt écrit" else "Le doigt fait défiler"
                IconButton(onClick = { state.fingerDraws = !state.fingerDraws }) {
                    Icon(
                        if (state.fingerDraws) Icons.Filled.TouchApp else Icons.Filled.PanTool,
                        contentDescription = fingerLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val opt = optionsFor
        if (opt != null && opt == state.tool && opt != Tool.LASSO) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
                modifier = Modifier.padding(top = 8.dp).width(300.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    when (opt) {
                        Tool.PEN -> {
                            OptionTitle("Couleur")
                            ColorGrid(InkColors, state.penColor) { state.penColor = it }
                            OptionTitle("Épaisseur")
                            SizeSlider(state.penWidth, 1.5f..14f, Color(state.penColor)) { state.penWidth = it }
                        }
                        Tool.HIGHLIGHTER -> {
                            OptionTitle("Couleur")
                            ColorGrid(HighlighterColors, state.highlighterColor) { state.highlighterColor = it }
                            OptionTitle("Épaisseur")
                            SizeSlider(state.highlighterWidth, 12f..60f, Color(state.highlighterColor).copy(alpha = 0.5f)) { state.highlighterWidth = it }
                        }
                        Tool.ERASER -> {
                            OptionTitle("Mode")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = state.eraserMode == EraserMode.PARTIAL,
                                    onClick = { state.eraserMode = EraserMode.PARTIAL },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) { Text("Partielle") }
                                SegmentedButton(
                                    selected = state.eraserMode == EraserMode.STROKE,
                                    onClick = { state.eraserMode = EraserMode.STROKE },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) { Text("Trait entier") }
                            }
                            OptionTitle("Taille")
                            SizeSlider(state.eraserSize, 6f..60f, MaterialTheme.colorScheme.outline) { state.eraserSize = it }
                        }
                        Tool.TEXT -> {
                            OptionTitle("Couleur")
                            ColorGrid(InkColors, state.textColor) { state.textColor = it }
                            OptionTitle("Taille du texte")
                            Slider(value = state.textSize, onValueChange = { state.textSize = it }, valueRange = 18f..72f)
                            Text("Touchez la page pour écrire, touchez un texte pour le modifier.", style = MaterialTheme.typography.bodySmall)
                        }
                        Tool.LASSO -> Unit
                    }
                }
            }
        } else if (state.tool == Tool.LASSO && state.selection == null) {
            Hint("Entourez des éléments pour les sélectionner")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ToolButton(active: Boolean, label: String, icon: ImageVector, accent: Color?, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(2.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active && accent != null) accent else if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accent != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(width = 14.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

@Composable
private fun OptionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorGrid(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(
                        if (c == selected) 3.dp else 1.dp,
                        if (c == selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                        CircleShape,
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .clickable { onPick(c) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SizeSlider(value: Float, range: ClosedFloatingPointRange<Float>, color: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val d = (4f + 30f * (value - range.start) / (range.endInclusive - range.start)).dp
            Box(Modifier.size(d).clip(CircleShape).background(color))
        }
    }
}

private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Gomme", defaultWidth = 24.dp, defaultHeight = 24.dp,
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

private val LassoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Lasso", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12f, 4f)
            curveTo(17f, 4f, 21f, 6.5f, 21f, 10f)
            curveTo(21f, 13.5f, 17f, 16f, 12f, 16f)
            curveTo(7f, 16f, 3f, 13.5f, 3f, 10f)
            curveTo(3f, 6.5f, 7f, 4f, 12f, 4f)
            close()
            moveTo(8f, 15f)
            curveTo(6f, 17f, 7f, 20f, 9.5f, 20.5f)
        }
    }.build()
}
