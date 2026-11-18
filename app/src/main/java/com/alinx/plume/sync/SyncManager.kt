package com.alinx.plume.sync

import android.content.Context
import android.os.Build
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder

/** État affiché dans l'appli. */
data class SyncStatus(
    /** Nom du PC associé, ou null si aucun. */
    val pcName: String? = null,
    val running: Boolean = false,
    val lastSync: Long = 0,
    /** Le PC n'a pas répondu à la dernière tentative (autre réseau, PC éteint…). */
    val unreachable: Boolean = false,
    val error: String? = null,
)

/** Un PC trouvé sur le Wi-Fi. */
data class FoundPc(val id: String, val name: String, val address: String, val port: Int) {
    val baseUrl get() = "http://$address:$port"
}

/**
 * Synchronisation directe avec le programme Plume du PC, sur le même Wi-Fi, sans serveur en ligne.
 * Règle simple : pour chaque note, la version modifiée le plus récemment gagne.
 */
object SyncManager {
    private const val DISCOVERY_PORT = 8766
    private const val HTTP_PORT = 8765

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var repo: NoteRepository
    private lateinit var prefs: android.content.SharedPreferences

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var loop: Job? = null
    private var debounce: Job? = null
    @Volatile private var again = false

    fun init(context: Context) {
        if (::repo.isInitialized) return
        repo = NoteRepository.get(context)
        prefs = context.getSharedPreferences("plume_pc", Context.MODE_PRIVATE)
        _status.value = SyncStatus(prefs.getString("pcName", null), lastSync = prefs.getLong("lastSync", 0))
        repo.onLocalChange = { requestSync(delayMs = 3_000) }
    }

    private val paired get() = prefs.getString("token", null) != null
    private fun api() = SyncApi(prefs.getString("baseUrl", "")!!, prefs.getString("token", null))

    // ---- Recherche du PC et association ----

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (ia.address is Inet4Address && b != null) out += b
                }
            }
        }
        return out.distinct()
    }

    /** Envoie « PLUME? » sur le Wi-Fi et liste les PC qui répondent. */
    suspend fun discover(timeoutMs: Int = 1_800): List<FoundPc> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, FoundPc>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val msg = "PLUME?".toByteArray()
                val targets = broadcastAddresses()
                val end = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (t in targets) runCatching { socket.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) }
                        nextSend = System.currentTimeMillis() + 600
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val o = json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject
                        if (o["app"]?.jsonPrimitive?.content != "plume") continue
                        val id = o["id"]!!.jsonPrimitive.content
                        found[id] = FoundPc(
                            id, o["name"]?.jsonPrimitive?.content ?: "PC",
                            packet.address.hostAddress ?: continue, o["port"]?.jsonPrimitive?.int ?: HTTP_PORT,
                        )
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }
        found.values.toList()
    }

    /** Adresse tapée à la main (si la recherche ne trouve rien). */
    suspend fun probe(address: String): FoundPc? = withContext(Dispatchers.IO) {
        val host = address.trim().removePrefix("http://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return@withContext null
        runCatching {
            val o = json.parseToJsonElement(SyncApi("http://$host:$HTTP_PORT", null).json("GET", "/hub/info", timeoutMs = 4_000)).jsonObject
            FoundPc(o["id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.content ?: "PC", host, HTTP_PORT)
        }.getOrNull()
    }

    /** Associe ce téléphone au PC avec le code affiché sur le PC. Renvoie un message d'erreur, ou null. */
    suspend fun pair(pc: FoundPc, code: String): String? = withContext(Dispatchers.IO) {
        val device = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").ifBlank { "Téléphone" }
        val body = buildJsonObject { put("code", code.trim()); put("device", device) }.toString()
        try {
            val res = json.parseToJsonElement(SyncApi(pc.baseUrl, null).json("POST", "/hub/pair", body)).jsonObject
            prefs.edit()
                .putString("token", res["token"]!!.jsonPrimitive.content)
                .putString("baseUrl", pc.baseUrl)
                .putString("pcId", pc.id)
                .putString("pcName", pc.name)
                .remove("uploaded").putLong("lastSync", 0)
                .commit()
            _status.value = SyncStatus(pc.name)
            requestSync(0)
            null
        } catch (e: SyncApi.HttpError) {
            when (e.code) {
                401 -> "Code incorrect."
                409 -> "Sur le PC, cliquez d'abord sur « Associer mon téléphone »."
                429 -> "Trop d'essais : patientez une minute."
                else -> "Erreur du PC (${e.code})."
            }
        } catch (e: IOException) {
            "PC injoignable. Vérifiez que les deux sont sur le même Wi-Fi."
        }
    }

    fun unpair() {
        prefs.edit().clear().apply()
        _status.value = SyncStatus()
    }

    // ---- Déclenchement ----

    /** Synchronise régulièrement tant que l'appli est à l'écran. */
    fun startAuto() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                syncNow()
                delay(30_000)
            }
        }
    }

    fun stopAuto() {
        loop?.cancel()
        loop = null
        requestSync(0)
    }

    fun requestSync(delayMs: Long = 0) {
        if (!::prefs.isInitialized || !paired) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    private suspend fun syncNow() {
        if (!paired) return
        if (lock.isLocked) { again = true; return }
        lock.withLock {
            do {
                again = false
                _status.update { it.copy(running = true) }
                var unreachable = false
                val error = try {
                    try {
                        syncOnce()
                    } catch (e: IOException) {
                        if (e is SyncApi.HttpError) throw e
                        // Le PC a peut-être changé d'adresse : on le cherche sur le Wi-Fi.
                        if (!relocate()) throw e
                        syncOnce()
                    }
                    null
                } catch (e: SyncApi.HttpError) {
                    if (e.code == 401) {
                        unpair()
                        "Ce téléphone n'est plus associé au PC."
                    } else "Erreur du PC (${e.code})."
                } catch (e: IOException) {
                    unreachable = true
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur de synchronisation."
                }
                val now = System.currentTimeMillis()
                val ok = error == null && !unreachable
                if (ok) prefs.edit().putLong("lastSync", now).apply()
                _status.update {
                    it.copy(running = false, error = error, unreachable = unreachable, lastSync = if (ok) now else it.lastSync)
                }
            } while (again && error == null && !unreachable)
        }
    }

    private suspend fun relocate(): Boolean {
        val id = prefs.getString("pcId", null) ?: return false
        val pc = discover(1_500).firstOrNull { it.id == id } ?: return false
        prefs.edit().putString("baseUrl", pc.baseUrl).putString("pcName", pc.name).apply()
        _status.update { it.copy(pcName = pc.name) }
        return true
    }

    // ---- Synchronisation ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun getRows(path: String): JsonArray = json.parseToJsonElement(api().json("GET", path)).jsonArray

    private fun post(path: String, rows: List<JsonElement>) {
        if (rows.isEmpty()) return
        api().json("POST", path, buildJsonArray { rows.forEach { add(it) } }.toString())
    }

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
        put("id", id); put("updated_at", at); put("deleted", true)
        put(dataKey, JsonObject(emptyMap()))
    }

    private suspend fun syncOnce() {
        syncFolders()
        syncNotes()
    }

    private fun syncFolders() {
        val remote = parseRemote(getRows("/hub/folders"), "data")
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
        post("/hub/folders", push)
        forget.forEach { repo.forgetDeletedFolder(it) }
    }

    private fun applyFolder(r: Remote) {
        val data = r.data ?: return
        val f = runCatching { json.decodeFromJsonElement(Folder.serializer(), data) }.getOrNull() ?: return
        repo.applyRemoteFolder(f.copy(id = r.id, updatedAt = r.updatedAt))
    }

    private fun folderRow(f: Folder) = buildJsonObject {
        put("id", f.id); put("updated_at", f.updatedAt); put("deleted", false)
        put("data", json.encodeToJsonElement(Folder.serializer(), f))
    }

    private suspend fun syncNotes() {
        val remote = parseRemote(getRows("/hub/notes"), "meta")
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
                    else -> { post("/hub/notes", listOf(tombRow(r.id, tomb, "meta"))); repo.forgetDeletedNote(r.id) }
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

    private fun assetPath(name: String) = "/hub/assets/${enc(name)}"

    private suspend fun pushNote(meta: NoteMeta) {
        val content = repo.loadContent(meta.id)
        val uploaded = prefs.getStringSet("uploaded", emptySet())!!.toMutableSet()
        for (a in assetsOf(content)) {
            if (a in uploaded) continue
            val f = Bitmaps.file(a)
            if (!f.exists()) continue
            api().upload(assetPath(a), f)
            uploaded += a
            prefs.edit().putStringSet("uploaded", HashSet(uploaded)).apply()
        }
        post("/hub/notes", listOf(buildJsonObject {
            put("id", meta.id); put("updated_at", meta.updatedAt); put("deleted", false)
            put("meta", json.encodeToJsonElement(NoteMeta.serializer(), meta))
            put("content", json.encodeToJsonElement(NoteContent.serializer(), content))
        }))
    }

    private suspend fun pullNote(id: String, updatedAt: Long) {
        val o = try {
            json.parseToJsonElement(api().json("GET", "/hub/notes/${enc(id)}")).jsonObject
        } catch (e: SyncApi.HttpError) {
            if (e.code == 404) return else throw e
        }
        val meta = json.decodeFromJsonElement(NoteMeta.serializer(), o["meta"]!!).copy(id = id, updatedAt = updatedAt)
        val contentEl = o["content"]?.takeIf { it !is JsonNull }
        val content = if (contentEl != null) json.decodeFromJsonElement(NoteContent.serializer(), contentEl) else NoteContent()
        val uploaded = prefs.getStringSet("uploaded", emptySet())!!.toMutableSet()
        for (a in assetsOf(content)) {
            val f = Bitmaps.file(a)
            if (f.exists() || api().download(assetPath(a), f)) uploaded += a
        }
        prefs.edit().putStringSet("uploaded", HashSet(uploaded)).apply()
        if (id == repo.editingNoteId) return
        repo.applyRemoteNote(meta.copy(pageCount = content.pages.size), content)
    }
}
