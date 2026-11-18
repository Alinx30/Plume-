package com.alinx.plume.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.alinx.plume.render.Bitmaps
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stockage local des carnets et des notes, en fichiers JSON dans le dossier privé de l'appli. */
class NoteRepository private constructor(private val context: Context) {

    init {
        Bitmaps.init(context)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root = File(context.filesDir, "plume").apply { mkdirs() }
    private val notesDir = File(root, "notes").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Mutex()
    private val pending = ConcurrentHashMap<String, NoteContent>()
    private val prefs = context.getSharedPreferences("plume", Context.MODE_PRIVATE)

    /** Papier des nouvelles notes : le dernier choisi. */
    var defaultPaper: PaperSpec
        get() = PaperSpec(
            runCatching { PaperStyle.valueOf(prefs.getString("paper", null) ?: "LINED") }.getOrDefault(PaperStyle.LINED),
            prefs.getLong("paperColor", 0xFFFFFFFF),
        )
        set(v) {
            prefs.edit().putString("paper", v.style.name).putLong("paperColor", v.color).apply()
        }

    private val _index = MutableStateFlow(readIndex())
    val index: StateFlow<LibraryIndex> = _index.asStateFlow()

    private fun readIndex(): LibraryIndex {
        val loaded = runCatching {
            json.decodeFromString(LibraryIndex.serializer(), indexFile.readText())
        }.getOrNull() ?: return LibraryIndex()
        // Migration v0.1 : carnets -> dossiers.
        if (loaded.notebooks.isNotEmpty() && loaded.folders.isEmpty()) {
            return loaded.copy(folders = loaded.notebooks, notebooks = emptyList())
        }
        return loaded
    }

    private fun updateIndex(transform: (LibraryIndex) -> LibraryIndex) {
        _index.updateAndGet(transform)
        scope.launch {
            ioLock.withLock {
                writeAtomic(indexFile, json.encodeToString(LibraryIndex.serializer(), _index.value))
            }
        }
    }

    // ---- Dossiers ----

    fun createFolder(name: String, parentId: String?): Folder {
        val f = Folder(
            id = newId(),
            name = name.trim().ifBlank { "Dossier" },
            parentId = parentId,
            color = FolderColors[_index.value.folders.size % FolderColors.size],
            createdAt = now(),
        )
        updateIndex { it.copy(folders = it.folders + f) }
        return f
    }

    fun renameFolder(id: String, name: String) = updateIndex { idx ->
        idx.copy(folders = idx.folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    fun setFolderColor(id: String, color: Long) = updateIndex { idx ->
        idx.copy(folders = idx.folders.map { if (it.id == id) it.copy(color = color) else it })
    }

    /** Le dossier et tous ses sous-dossiers. */
    fun folderTree(id: String): Set<String> {
        val all = _index.value.folders
        val result = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (f in all) if (f.parentId in result && result.add(f.id)) grew = true
        }
        return result
    }

    /** Supprime le dossier, ses sous-dossiers et toutes leurs notes. */
    fun deleteFolder(id: String) {
        val tree = folderTree(id)
        val removed = _index.value.notes.filter { it.folderId in tree }.map { it.id }
        removed.forEach { pending.remove(it) }
        updateIndex { idx ->
            idx.copy(
                folders = idx.folders.filterNot { it.id in tree },
                notes = idx.notes.filterNot { it.folderId in tree },
            )
        }
        scope.launch { ioLock.withLock { removed.forEach { noteFile(it).delete() } } }
    }

    // ---- Notes ----

    fun createNote(folderId: String?, paper: PaperSpec = defaultPaper): NoteMeta {
        val date = SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date())
        val t = now()
        val note = NoteMeta(newId(), folderId, "Note du $date", t, t, paper.style, paperColor = paper.color)
        updateIndex { it.copy(notes = it.notes + note) }
        return note
    }

    fun renameNote(id: String, title: String) = updateNote(id) { it.copy(title = title.trim().ifBlank { it.title }) }

    fun moveNote(id: String, folderId: String?) = updateNote(id) { it.copy(folderId = folderId) }

    fun setPaper(id: String, paper: PaperSpec) = updateNote(id) {
        it.copy(paper = paper.style, paperColor = paper.color, updatedAt = now())
    }

    suspend fun duplicateNote(id: String): NoteMeta? {
        val meta = _index.value.notes.firstOrNull { it.id == id } ?: return null
        val content = loadContent(id)
        val t = now()
        val copy = meta.copy(id = newId(), title = meta.title + " (copie)", createdAt = t, updatedAt = t)
        updateIndex { it.copy(notes = it.notes + copy) }
        saveContent(copy.id, content)
        return copy
    }

    fun deleteNote(id: String) {
        pending.remove(id)
        updateIndex { idx -> idx.copy(notes = idx.notes.filterNot { it.id == id }) }
        scope.launch { ioLock.withLock { noteFile(id).delete() } }
    }

    private fun updateNote(id: String, transform: (NoteMeta) -> NoteMeta) = updateIndex { idx ->
        idx.copy(notes = idx.notes.map { if (it.id == id) transform(it) else it })
    }

    suspend fun loadContent(id: String): NoteContent {
        pending[id]?.let { return it }
        return withContext(Dispatchers.IO) {
            ioLock.withLock {
                runCatching {
                    json.decodeFromString(NoteContent.serializer(), noteFile(id).readText())
                }.getOrNull() ?: NoteContent()
            }
        }
    }

    fun saveContent(id: String, content: NoteContent) {
        pending[id] = content
        updateNote(id) { it.copy(updatedAt = now(), pageCount = content.pages.size) }
        scope.launch {
            ioLock.withLock {
                val latest = pending.remove(id) ?: return@withLock
                writeAtomic(noteFile(id), json.encodeToString(NoteContent.serializer(), latest))
            }
        }
    }

    // ---- Import de fichiers ----

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Copie un PDF dans l'appli et crée une note dont chaque page est une page du PDF. */
    suspend fun importPdf(uri: Uri, folderId: String?): NoteMeta? = withContext(Dispatchers.IO) {
        val asset = "${newId()}.pdf"
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                Bitmaps.file(asset).outputStream().use { input.copyTo(it) }
            } ?: error("lecture impossible")
        }.isSuccess
        if (!ok) return@withContext null
        val count = runCatching { Bitmaps.pdfPageCount(asset) }.getOrDefault(0)
        if (count <= 0) {
            Bitmaps.file(asset).delete()
            return@withContext null
        }
        val name = displayName(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "PDF importé"
        val t = now()
        val note = NoteMeta(newId(), folderId, name, t, t, PaperStyle.BLANK, count)
        updateIndex { it.copy(notes = it.notes + note) }
        saveContent(note.id, NoteContent((0 until count).map { Page(pdf = PdfRef(asset, it)) }))
        note
    }

    /** Copie une photo dans l'appli (réduite à 2048 px au plus). Renvoie (nom, largeur, hauteur). */
    suspend fun importImage(uri: Uri): Triple<String, Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val bmp = rotateFromExif(uri, raw)
            val asset = "${newId()}.jpg"
            Bitmaps.file(asset).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Triple(asset, bmp.width, bmp.height)
        }.getOrNull()
    }

    private fun rotateFromExif(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return bmp
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun noteFile(id: String) = File(notesDir, "$id.json")

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        @Volatile
        private var instance: NoteRepository? = null

        fun get(context: Context): NoteRepository =
            instance ?: synchronized(this) {
                instance ?: NoteRepository(context.applicationContext).also { instance = it }
            }

        private fun newId() = UUID.randomUUID().toString()
        private fun now() = System.currentTimeMillis()
    }
}
