package com.alinx.plume.sync

import android.content.Context
import com.alinx.plume.BuildConfig
import com.alinx.plume.data.Folder
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.NoteMeta
import com.alinx.plume.data.NoteRepository
import com.alinx.plume.render.Bitmaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder

/** État affiché dans l'appli. */
data class SyncStatus(
    val configured: Boolean,
    val email: String? = null,
    val running: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
)

/**
 * Synchronisation des dossiers, notes et fichiers joints avec le compte Plume en ligne.
 * Règle simple : pour chaque note, la version modifiée le plus récemment gagne.
 */
object SyncManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var repo: NoteRepository
    private lateinit var prefs: android.content.SharedPreferences
    private val api = SyncApi(BuildConfig.SYNC_URL, BuildConfig.SYNC_KEY)

    val configured: Boolean get() = BuildConfig.SYNC_URL.startsWith("https://") && BuildConfig.SYNC_KEY.isNotBlank()
    val webUrl: String get() = BuildConfig.WEB_URL

    private val _status = MutableStateFlow(SyncStatus(configured))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var loop: Job? = null
    private var debounce: Job? = null
    @Volatile private var again = false

    fun init(context: Context) {
        if (::repo.isInitialized) return
        repo = NoteRepository.get(context)
        prefs = context.getSharedPreferences("plume_sync", Context.MODE_PRIVATE)
        _status.value = SyncStatus(configured, prefs.getString("email", null), lastSync = prefs.getLong("lastSync", 0))
        repo.onLocalChange = { requestSync(delayMs = 4_000) }
    }

    private val signedIn get() = prefs.getString("refresh", null) != null

    // ---- Compte ----

    /** Connexion ou création de compte. Renvoie un message d'erreur, ou null si tout va bien. */
    suspend fun signIn(email: String, password: String, create: Boolean): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext "La synchronisation n'est pas encore configurée."
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }.toString()
        try {
            val path = if (create) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
            val res = json.parseToJsonElement(api.json("POST", path, null, body)).jsonObject
            if (res["access_token"] == null) {
                return@withContext "Compte créé. Ouvrez le lien reçu par e-mail, puis connectez-vous."
            }
            saveSession(res)
            prefs.edit().putString("email", email.trim()).remove("uploaded").putLong("lastSync", 0).apply()
            _status.update { it.copy(email = email.trim(), error = null) }
            requestSync(0)
            null
        } catch (e: SyncApi.HttpError) {
            authMessage(e)
        } catch (e: IOException) {
            "Pas de connexion internet."
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _status.value = SyncStatus(configured)
    }

    private fun authMessage(e: SyncApi.HttpError): String {
        val b = e.body.lowercase()
        return when {
            "invalid login" in b || "invalid_credentials" in b -> "E-mail ou mot de passe incorrect."
            "already registered" in b || "user_already_exists" in b -> "Ce compte existe déjà : utilisez « Se connecter »."
            "password" in b && ("6" in b || "weak" in b) -> "Mot de passe trop court (6 caractères au moins)."
            "email not confirmed" in b -> "Ouvrez d'abord le lien reçu par e-mail."
            "invalid" in b && "email" in b -> "Adresse e-mail invalide."
            else -> "Erreur du serveur (${e.code})."
        }
    }

    private fun saveSession(res: JsonObject) {
        val expiresIn = res["expires_in"]?.jsonPrimitive?.long ?: 3600
        val user = res["user"]?.jsonObject
        val editor = prefs.edit()
            .putString("access", res["access_token"]!!.jsonPrimitive.content)
            .putString("refresh", res["refresh_token"]!!.jsonPrimitive.content)
            .putLong("expires", System.currentTimeMillis() + (expiresIn - 60) * 1000)
        val userId = user?.get("id")?.jsonPrimitive?.content
        if (userId != null) editor.putString("uid", userId)
        editor.commit()
    }

    private fun token(): String {
        val access = prefs.getString("access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("expires", 0)) return access
        val refresh = prefs.getString("refresh", null) ?: throw IllegalStateException("non connecté")
        try {
            val body = buildJsonObject { put("refresh_token", refresh) }.toString()
            saveSession(json.parseToJsonElement(api.json("POST", "/auth/v1/token?grant_type=refresh_token", null, body)).jsonObject)
        } catch (e: SyncApi.HttpError) {
            if (e.code in 400..499) {
                signOut()
                _status.update { it.copy(error = "Session expirée : reconnectez-vous.") }
            }
            throw e
        }
        return prefs.getString("access", null)!!
    }

    // ---- Déclenchement ----

    /** Synchronise régulièrement tant que l'appli est à l'écran. */
    fun startAuto() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                syncNow()
                delay(60_000)
            }
        }
    }

    fun stopAuto() {
        loop?.cancel()
        loop = null
        requestSync(0)
    }

    fun requestSync(delayMs: Long = 0) {
        if (!configured || !::prefs.isInitialized || !signedIn) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    private suspend fun syncNow() {
        if (!configured || !signedIn) return
        if (lock.isLocked) { again = true; return }
        lock.withLock {
            do {
                again = false
                _status.update { it.copy(running = true) }
                val error = try {
                    syncOnce()
                    null
                } catch (e: SyncApi.HttpError) {
                    if (e.code == 404) "Le serveur n'est pas prêt (tables manquantes)." else "Erreur du serveur (${e.code})."
                } catch (e: IOException) {
                    "Pas de connexion internet."
                } catch (e: Exception) {
                    e.message ?: "Erreur de synchronisation."
                }
                val now = System.currentTimeMillis()
                if (error == null) prefs.edit().putLong("lastSync", now).apply()
                _status.update { it.copy(running = false, error = error, lastSync = if (error == null) now else it.lastSync) }
            } while (again && error == null)
        }
    }

    // ---- Synchronisation ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getRows(path: String): JsonArray = json.parseToJsonElement(api.json("GET", path, token())).jsonArray

    private fun upsert(table: String, rows: List<JsonElement>) {
        if (rows.isEmpty()) return
        val body = buildJsonArray { rows.forEach { add(it) } }.toString()
        api.json(
            "POST", "/rest/v1/$table?on_conflict=user_id,id", token(), body,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    private val uid get() = prefs.getString("uid", null) ?: throw IllegalStateException("compte inconnu")

    private class Remote(val id: String, val updatedAt: Long, val deleted: Boolean, val data: JsonElement?)

    private fun parseRemote(rows: JsonArray, dataKey: String) = rows.map {
        val o = it.jsonObject
        Remote(
            o["id"]!!.jsonPrimitive.content,
            o["updated_at"]!!.jsonPrimitive.long,
            o["deleted"]?.jsonPrimitive?.boolean ?: false,
            o[dataKey]?.takeIf { d -> d !is JsonNull },
        )
    }

    private fun tombRow(id: String, at: Long, dataKey: String) = buildJsonObject {
        put("user_id", uid); put("id", id); put("updated_at", at); put("deleted", true)
        put(dataKey, JsonObject(emptyMap()))
    }

    private suspend fun syncOnce() {
        token()
        syncFolders()
        syncNotes()
    }

    private fun syncFolders() {
        val remote = parseRemote(getRows("/rest/v1/folders?select=id,data,updated_at,deleted"), "data")
        val local = repo.index.value.folders.associateBy { it.id }
        val tombs = repo.deletedFolders()
        val push = mutableListOf<JsonElement>()
        val forget = mutableListOf<String>()
        for (r in remote) {
            val l = local[r.id]
            val tomb = tombs[r.id]
            when {
                tomb != null -> when {
                    r.deleted -> forget += r.id
                    r.updatedAt > tomb -> { forget += r.id; applyFolder(r) }
                    else -> { push += tombRow(r.id, tomb, "data"); forget += r.id }
                }
                r.deleted -> if (l != null && l.updatedAt <= r.updatedAt) repo.removeFolderLocally(r.id)
                l == null || r.updatedAt > l.updatedAt -> applyFolder(r)
                l.updatedAt > r.updatedAt -> push += folderRow(l)
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        for (l in local.values) if (l.id !in remoteIds) push += folderRow(l)
        for (id in tombs.keys) if (id !in remoteIds) forget += id
        upsert("folders", push)
        forget.forEach { repo.forgetDeletedFolder(it) }
    }

    private fun applyFolder(r: Remote) {
        val data = r.data ?: return
        val f = runCatching { json.decodeFromJsonElement(Folder.serializer(), data) }.getOrNull() ?: return
        repo.applyRemoteFolder(f.copy(id = r.id, updatedAt = r.updatedAt))
    }

    private fun folderRow(f: Folder) = buildJsonObject {
        put("user_id", uid); put("id", f.id); put("updated_at", f.updatedAt); put("deleted", false)
        put("data", json.encodeToJsonElement(Folder.serializer(), f))
    }

    private suspend fun syncNotes() {
        val remote = parseRemote(getRows("/rest/v1/notes?select=id,meta,updated_at,deleted"), "meta")
        val local = repo.index.value.notes.associateBy { it.id }
        val tombs = repo.deletedNotes()
        val busy = { id: String -> id == repo.editingNoteId || repo.hasPendingWrite(id) }
        for (r in remote) {
            val l = local[r.id]
            val tomb = tombs[r.id]
            when {
                tomb != null -> when {
                    r.deleted -> repo.forgetDeletedNote(r.id)
                    r.updatedAt > tomb -> { repo.forgetDeletedNote(r.id); pullNote(r.id, r.updatedAt) }
                    else -> { upsert("notes", listOf(tombRow(r.id, tomb, "meta"))); repo.forgetDeletedNote(r.id) }
                }
                r.deleted -> if (l != null && l.updatedAt <= r.updatedAt && !busy(r.id)) repo.removeNoteLocally(r.id)
                l == null -> pullNote(r.id, r.updatedAt)
                r.updatedAt > l.updatedAt -> if (!busy(r.id)) pullNote(r.id, r.updatedAt)
                l.updatedAt > r.updatedAt -> pushNote(l)
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        for (l in local.values) if (l.id !in remoteIds) pushNote(l)
        for (id in tombs.keys) if (id !in remoteIds) repo.forgetDeletedNote(id)
    }

    private fun assetsOf(c: NoteContent): Set<String> = buildSet {
        c.pages.forEach { p ->
            p.pdf?.let { add(it.asset) }
            p.images.forEach { add(it.asset) }
        }
        c.recordings.forEach { add(it.asset) }
    }

    private fun assetPath(name: String) = "/storage/v1/object/assets/${enc(uid)}/${enc(name)}"

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private suspend fun pushNote(meta: NoteMeta) {
        val content = repo.loadContent(meta.id)
        val uploaded = prefs.getStringSet("uploaded", emptySet())!!.toMutableSet()
        for (a in assetsOf(content)) {
            if (a in uploaded) continue
            val f = Bitmaps.file(a)
            if (!f.exists()) continue
            api.upload(assetPath(a), token(), f, mimeOf(a))
            uploaded += a
            prefs.edit().putStringSet("uploaded", HashSet(uploaded)).apply()
        }
        upsert("notes", listOf(buildJsonObject {
            put("user_id", uid); put("id", meta.id); put("updated_at", meta.updatedAt); put("deleted", false)
            put("meta", json.encodeToJsonElement(NoteMeta.serializer(), meta))
            put("content", json.encodeToJsonElement(NoteContent.serializer(), content))
        }))
    }

    private suspend fun pullNote(id: String, updatedAt: Long) {
        val rows = getRows("/rest/v1/notes?id=eq.${enc(id)}&select=meta,content")
        val o = rows.firstOrNull()?.jsonObject ?: return
        val meta = json.decodeFromJsonElement(NoteMeta.serializer(), o["meta"]!!).copy(id = id, updatedAt = updatedAt)
        val contentEl = o["content"]?.takeIf { it !is JsonNull }
        val content = if (contentEl != null) json.decodeFromJsonElement(NoteContent.serializer(), contentEl) else NoteContent()
        val uploaded = prefs.getStringSet("uploaded", emptySet())!!.toMutableSet()
        for (a in assetsOf(content)) {
            val f = Bitmaps.file(a)
            if (!f.exists() && api.download(assetPath(a), token(), f)) uploaded += a
            else if (f.exists()) uploaded += a
        }
        prefs.edit().putStringSet("uploaded", HashSet(uploaded)).apply()
        if (id == repo.editingNoteId) return
        repo.applyRemoteNote(meta.copy(pageCount = content.pages.size), content)
    }
}
