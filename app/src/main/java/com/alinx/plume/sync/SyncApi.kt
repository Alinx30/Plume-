package com.alinx.plume.sync

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Appels HTTP au service de synchronisation (Supabase : comptes, base de données, fichiers). */
class SyncApi(private val baseUrl: String, private val key: String) {

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code : $body")

    private fun open(method: String, path: String, token: String?, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("apikey", key)
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun finish(conn: HttpURLConnection): ByteArray {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw HttpError(code, String(bytes))
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    /** Requête JSON ; renvoie le corps de la réponse en texte. */
    fun json(method: String, path: String, token: String?, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val conn = open(method, path, token, headers)
        if (body != null) {
            val bytes = body.toByteArray()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return String(finish(conn))
    }

    /** Envoie un fichier (remplace la version existante s'il y en a une). */
    fun upload(path: String, token: String, file: File, contentType: String) {
        val conn = open("POST", path, token, mapOf("x-upsert" to "true", "Content-Type" to contentType))
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
        finish(conn)
    }

    /** Télécharge un fichier ; renvoie false s'il n'existe pas sur le serveur. */
    fun download(path: String, token: String, dest: File): Boolean {
        val conn = open("GET", path, token, emptyMap())
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.close()
                if (code == 400 || code == 404) return false
                throw HttpError(code, "")
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
            return true
        } finally {
            conn.disconnect()
        }
    }
}
