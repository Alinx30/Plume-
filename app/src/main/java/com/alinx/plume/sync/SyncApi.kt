package com.alinx.plume.sync

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Appels HTTP au programme Plume du PC, sur le réseau Wi-Fi local. */
class SyncApi(private val baseUrl: String, private val token: String?) {

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code : $body")

    private fun open(method: String, path: String, timeoutMs: Int = 8_000): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = 60_000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
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
    fun json(method: String, path: String, body: String? = null, timeoutMs: Int = 8_000): String {
        val conn = open(method, path, timeoutMs)
        if (body != null) {
            val bytes = body.toByteArray()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return String(finish(conn))
    }

    /** Envoie un fichier joint (PDF, photo, audio). */
    fun upload(path: String, file: File) {
        val conn = open("PUT", path)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
        finish(conn)
    }

    /** Télécharge un fichier joint ; renvoie false s'il n'existe pas sur le PC. */
    fun download(path: String, dest: File): Boolean {
        val conn = open("GET", path)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.close()
                if (code == 404) return false
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
