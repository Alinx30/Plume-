package com.alinx.plume.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import com.alinx.plume.data.PdfRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Fichiers importés (PDF, photos) et leurs images en cache.
 * Le rendu se fait sur un fil dédié ; [version] change quand une image est prête.
 */
object Bitmaps {
    private lateinit var dir: File
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>()
    private val inFlight = HashSet<String>()
    private val buckets = intArrayOf(240, 600, 1200, 2000)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5).toInt().coerceAtLeast(16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Augmente quand une nouvelle image est disponible (pour redessiner). */
    val version = mutableIntStateOf(0)

    fun init(context: Context) {
        if (!::dir.isInitialized) {
            dir = File(context.filesDir, "plume/assets").apply { mkdirs() }
        }
    }

    fun file(asset: String) = File(dir, asset)

    private fun bucketFor(widthPx: Int): Int = buckets.firstOrNull { it >= widthPx } ?: buckets.last()

    /** Image de la page PDF, à peu près à la largeur demandée ; null si pas encore prête. */
    fun pdfPage(ref: PdfRef, widthPx: Int): Bitmap? = get("pdf:${ref.asset}#${ref.index}", widthPx) { w -> renderPdf(ref, w) }

    fun image(asset: String, widthPx: Int): Bitmap? = get("img:$asset", widthPx) { w -> decodeImage(asset, w) }

    private fun get(base: String, widthPx: Int, load: (Int) -> Bitmap?): Bitmap? {
        val bucket = bucketFor(widthPx)
        val key = "$base@$bucket"
        cache.get(key)?.let { return it }
        request(key) { load(bucket) }
        // En attendant, une version d'une autre résolution si on l'a.
        for (b in buckets.reversed()) cache.get("$base@$b")?.let { return it }
        return null
    }

    private fun request(key: String, load: () -> Bitmap?) {
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        scope.launch {
            val bmp = runCatching { load() }.getOrNull()
            if (bmp != null) cache.put(key, bmp)
            synchronized(inFlight) { inFlight.remove(key) }
            if (bmp != null) withContext(Dispatchers.Main) { version.intValue++ }
        }
    }

    /** Pour l'export PDF : rendu immédiat (à appeler hors du fil principal). */
    fun pdfPageBlocking(ref: PdfRef, widthPx: Int): Bitmap? = runBlocking(dispatcher) { runCatching { renderPdf(ref, widthPx) }.getOrNull() }

    fun imageBlocking(asset: String, widthPx: Int): Bitmap? = runBlocking(dispatcher) { runCatching { decodeImage(asset, widthPx) }.getOrNull() }

    /** Nombre de pages d'un PDF importé. */
    fun pdfPageCount(asset: String): Int = runBlocking(dispatcher) { renderer(asset)?.pageCount ?: 0 }

    /** Rapport hauteur / largeur de la page PDF. */
    fun pdfPageRatio(ref: PdfRef): Float = runBlocking(dispatcher) {
        val r = renderer(ref.asset) ?: return@runBlocking 1.414f
        if (ref.index >= r.pageCount) return@runBlocking 1.414f
        val page = r.openPage(ref.index)
        try { page.height.toFloat() / page.width.toFloat() } finally { page.close() }
    }

    private fun renderer(asset: String): PdfRenderer? {
        renderers[asset]?.let { return it.second }
        val f = file(asset)
        if (!f.exists()) return null
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        renderers[asset] = pfd to r
        return r
    }

    private fun renderPdf(ref: PdfRef, widthPx: Int): Bitmap? {
        val r = renderer(ref.asset) ?: return null
        if (ref.index >= r.pageCount) return null
        val page = r.openPage(ref.index)
        try {
            val h = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private fun decodeImage(asset: String, widthPx: Int): Bitmap? {
        val f = file(asset)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
