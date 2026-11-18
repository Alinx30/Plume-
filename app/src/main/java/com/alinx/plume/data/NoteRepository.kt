package com.alinx.plume.data

import android.content.Context
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
class NoteRepository private constructor(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root = File(context.filesDir, "plume").apply { mkdirs() }
    private val notesDir = File(root, "notes").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Mutex()
    private val pending = ConcurrentHashMap<String, NoteContent>()

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

    fun createNote(folderId: String?, paper: PaperStyle = PaperStyle.LINED): NoteMeta {
        val date = SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date())
        val t = now()
        val note = NoteMeta(newId(), folderId, "Note du $date", t, t, paper)
        updateIndex { it.copy(notes = it.notes + note) }
        return note
    }

    fun renameNote(id: String, title: String) = updateNote(id) { it.copy(title = title.trim().ifBlank { it.title }) }

    fun moveNote(id: String, folderId: String?) = updateNote(id) { it.copy(folderId = folderId) }

    fun setPaper(id: String, paper: PaperStyle) = updateNote(id) { it.copy(paper = paper, updatedAt = now()) }

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
