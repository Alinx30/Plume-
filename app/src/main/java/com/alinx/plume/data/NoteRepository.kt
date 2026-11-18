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
        }.getOrNull() ?: LibraryIndex()
        if (loaded.notebooks.isNotEmpty()) return loaded
        return loaded.copy(notebooks = listOf(defaultNotebook()))
    }

    private fun defaultNotebook() = Notebook(newId(), "Mes notes", NotebookColors[0], now())

    private fun updateIndex(transform: (LibraryIndex) -> LibraryIndex) {
        _index.updateAndGet(transform)
        scope.launch {
            ioLock.withLock {
                writeAtomic(indexFile, json.encodeToString(LibraryIndex.serializer(), _index.value))
            }
        }
    }

    // ---- Carnets ----

    fun createNotebook(name: String): Notebook {
        val nb = Notebook(
            id = newId(),
            name = name.trim().ifBlank { "Carnet" },
            color = NotebookColors[_index.value.notebooks.size % NotebookColors.size],
            createdAt = now(),
        )
        updateIndex { it.copy(notebooks = it.notebooks + nb) }
        return nb
    }

    fun renameNotebook(id: String, name: String) = updateIndex { idx ->
        idx.copy(notebooks = idx.notebooks.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    fun deleteNotebook(id: String) {
        val removed = _index.value.notes.filter { it.notebookId == id }.map { it.id }
        removed.forEach { pending.remove(it) }
        updateIndex { idx ->
            val nbs = idx.notebooks.filterNot { it.id == id }
            idx.copy(
                notebooks = nbs.ifEmpty { listOf(defaultNotebook()) },
                notes = idx.notes.filterNot { it.notebookId == id },
            )
        }
        scope.launch { ioLock.withLock { removed.forEach { noteFile(it).delete() } } }
    }

    // ---- Notes ----

    fun createNote(notebookId: String): NoteMeta {
        val date = SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date())
        val t = now()
        val note = NoteMeta(newId(), notebookId, "Note du $date", t, t)
        updateIndex { it.copy(notes = it.notes + note) }
        return note
    }

    fun renameNote(id: String, title: String) = updateNote(id) { it.copy(title = title.trim().ifBlank { it.title }) }

    fun moveNote(id: String, notebookId: String) = updateNote(id) { it.copy(notebookId = notebookId) }

    fun setPaper(id: String, paper: PaperStyle) = updateNote(id) { it.copy(paper = paper, updatedAt = now()) }

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
