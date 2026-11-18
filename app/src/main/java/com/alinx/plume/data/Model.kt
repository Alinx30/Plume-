package com.alinx.plume.data

import kotlinx.serialization.Serializable

/** Largeur et hauteur logiques d'une page (format A4). Les traits sont stockés dans ces unités. */
const val PAGE_W = 1000f
const val PAGE_H = 1414f

@Serializable
data class Notebook(
    val id: String,
    val name: String,
    val color: Long,
    val createdAt: Long,
)

@Serializable
enum class PaperStyle { BLANK, LINED, GRID, DOTS }

@Serializable
data class NoteMeta(
    val id: String,
    val notebookId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val paper: PaperStyle = PaperStyle.LINED,
    val pageCount: Int = 1,
)

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
)

@Serializable
data class Page(val strokes: List<Stroke> = emptyList())

@Serializable
data class NoteContent(val pages: List<Page> = listOf(Page()))

@Serializable
data class LibraryIndex(
    val notebooks: List<Notebook> = emptyList(),
    val notes: List<NoteMeta> = emptyList(),
)

val NotebookColors = listOf(
    0xFF3F5AA8, 0xFFE07A3F, 0xFF3E9C74, 0xFFB0457E, 0xFF7A5BC4, 0xFFC9A227,
)
