// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.util

enum class FileKind { Image, Text, Other }

/** Extension-based types; kept free of Android APIs so it is unit-testable. */
object MimeTypes {
    private val map = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "log" to "text/plain",
        "json" to "application/json", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html",
        "pdf" to "application/pdf", "mp4" to "video/mp4", "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/wav",
        "zip" to "application/zip", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "odt" to "application/vnd.oasis.opendocument.text",
    )
    private val textual = setOf("txt", "md", "csv", "log", "json", "xml", "html", "htm")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun mime(name: String): String = map[ext(name)] ?: "application/octet-stream"

    fun kind(name: String): FileKind {
        val e = ext(name)
        return when {
            map[e]?.startsWith("image/") == true && e != "heic" && e != "heif" -> FileKind.Image
            e in textual -> FileKind.Text
            else -> FileKind.Other
        }
    }
}
