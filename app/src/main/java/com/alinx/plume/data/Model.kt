package com.alinx.plume.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Largeur et hauteur logiques d'une page (format A4). Les traits sont stockés dans ces unités. */
const val PAGE_W = 1000f
const val PAGE_H = 1414f

/** Espace entre deux pages dans le défilement continu. */
const val PAGE_GAP = 36f

/** Générateur d'identifiants uniques pour les traits et zones de texte. */
object Ids {
    private var seq = System.currentTimeMillis() * 1000

    @Synchronized
    fun next(): Long = ++seq
}

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val color: Long = 0xFF3F5AA8,
    val createdAt: Long = 0,
)

@Serializable
enum class PaperStyle(val label: String) {
    BLANK("Blanc"),
    LINED("Lignes"),
    LINED_WIDE("Grandes lignes"),
    SEYES("Seyès"),
    GRID("Quadrillage"),
    GRID_SMALL("Petits carreaux"),
    MILLIMETER("Millimétré"),
    DOTS("Points"),
    CORNELL("Cornell"),
    MUSIC("Portées"),
}

/** Couleurs de papier proposées. */
val PaperColors = listOf(
    0xFFFFFFFF to "Blanc",
    0xFFFBF5E6 to "Crème",
    0xFFFFF7C2 to "Jaune",
    0xFFE9EEF3 to "Gris bleu",
    0xFF232733 to "Ardoise",
    0xFF121212 to "Noir",
)

/** Style et couleur du papier d'une note. */
data class PaperSpec(val style: PaperStyle = PaperStyle.LINED, val color: Long = 0xFFFFFFFF) {
    val isDark: Boolean
        get() {
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            return r * 0.299f + g * 0.587f + b * 0.114f < 0.5f
        }
}

@Serializable
data class NoteMeta(
    val id: String,
    /** Dossier de la note, ou null pour « Non classées ». */
    @SerialName("notebookId") val folderId: String? = null,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val paper: PaperStyle = PaperStyle.LINED,
    val pageCount: Int = 1,
    val paperColor: Long = 0xFFFFFFFF,
) {
    val paperSpec: PaperSpec get() = PaperSpec(paper, paperColor)
}

@Serializable
enum class InkTool { PEN, HIGHLIGHTER }

/**
 * Un trait manuscrit. [points] contient des triplets (x, y, pression) à plat,
 * en coordonnées de page.
 */
@Serializable
data class Stroke(
    val id: Long,
    val tool: InkTool,
    val color: Long,
    val width: Float,
    val points: List<Float>,
    /** Enregistrement audio en cours quand le trait a été écrit, et instant (ms) dans cet enregistrement. */
    val rec: String? = null,
    val t: Long = -1,
)

/** Zone de texte tapé au clavier, en coordonnées de page. */
@Serializable
data class TextBox(
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val text: String,
    val size: Float = 30f,
    val color: Long = 0xFF1B1B1F,
)

/** Page d'un PDF importé, utilisée comme fond. */
@Serializable
data class PdfRef(val asset: String, val index: Int)

/** Photo posée sur la page, en coordonnées de page. */
@Serializable
data class ImageItem(
    val id: Long,
    val asset: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@Serializable
data class Page(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<ImageItem> = emptyList(),
    val pdf: PdfRef? = null,
) {
    fun hasContent() = strokes.isNotEmpty() || texts.isNotEmpty() || images.isNotEmpty() || pdf != null
}

/** Enregistrement audio attaché à une note. */
@Serializable
data class Recording(
    val id: String,
    val asset: String,
    val createdAt: Long,
    val durationMs: Long = 0,
)

@Serializable
data class NoteContent(
    val pages: List<Page> = listOf(Page()),
    val recordings: List<Recording> = emptyList(),
)

@Serializable
data class LibraryIndex(
    val folders: List<Folder> = emptyList(),
    /** Ancien format (v0.1) : les carnets deviennent des dossiers. */
    val notebooks: List<Folder> = emptyList(),
    val notes: List<NoteMeta> = emptyList(),
)

val FolderColors = listOf(
    0xFF3F5AA8, 0xFFE07A3F, 0xFF3E9C74, 0xFFB0457E, 0xFF7A5BC4, 0xFFC9A227, 0xFF4E8FB8, 0xFF8C6B4F,
)
