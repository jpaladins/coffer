// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import java.io.InputStream

/** Size of each native write while importing. */
const val IMPORT_CHUNK = 1 shl 20

fun childPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

private const val FORBIDDEN = "\"*/:<>?|\\"

/**
 * Makes a name from another app (SAF display name) valid on FAT/exFAT:
 * forbidden and control characters become '_', trailing dots and spaces go,
 * and long names are cut to 255 UTF-16 units keeping the extension.
 */
fun sanitizeName(raw: String): String {
    var s = raw.map { if (it < ' ' || it in FORBIDDEN) '_' else it }.joinToString("").trim().trimEnd('.', ' ')
    if (s.isEmpty() || s == "." || s == "..") s = "file"
    return fitName(s, "")
}

private const val MAX_NAME = 255

/**
 * Joins [base] + [suffix] + extension of [base] within 255 UTF-16 units:
 * the stem is cut (never inside a surrogate pair) and the result never ends
 * with a dot or space.
 */
private fun fitName(name: String, suffix: String): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 } ?: name.length
    val ext = name.substring(dot)
    var stem = name.substring(0, dot)
    val room = MAX_NAME - ext.length - suffix.length
    if (stem.length > room) {
        var cut = room
        if (cut > 0 && Character.isHighSurrogate(stem[cut - 1])) cut--
        stem = stem.substring(0, cut).trimEnd('.', ' ')
        if (stem.isEmpty()) stem = "file"
    }
    return "$stem$suffix$ext"
}

/** True when [path] exists (lookup is case-insensitive on FAT/exFAT). */
fun VaultHandle.exists(path: String): Boolean = try {
    stat(path)
    true
} catch (_: CofferException.NotFound) {
    false
}

/** [name] if free in [dir], else "name (1).ext", "name (2).ext", ... */
fun VaultHandle.uniqueName(dir: String, name: String): String {
    if (!exists(childPath(dir, name))) return name
    var i = 1
    while (true) {
        val candidate = fitName(name, " ($i)")
        if (!exists(childPath(dir, candidate))) return candidate
        i++
    }
}

/**
 * Copies [input] into the new file [path] in [IMPORT_CHUNK] writes. On any
 * failure, cancellation included, the partial file is removed (best effort)
 * so the volume never keeps a truncated copy. Returns the bytes written.
 */
fun VaultHandle.importStream(
    path: String,
    input: InputStream,
    isActive: () -> Boolean = { true },
    onProgress: (Long) -> Unit = {},
): Long {
    createFile(path)
    val buf = ByteArray(IMPORT_CHUNK)
    var written = 0L
    try {
        while (true) {
            if (!isActive()) throw CofferException.Cancelled()
            val n = input.readFully(buf)
            if (n <= 0) break
            if (n == buf.size) {
                write(path, written, buf)
            } else {
                val tail = buf.copyOf(n)
                write(path, written, tail)
                tail.fill(0)
            }
            written += n
            onProgress(written)
        }
        sync()
        return written
    } catch (e: Throwable) {
        runCatching { remove(path) }
        throw e
    } finally {
        buf.fill(0)
    }
}

/** Fills [buf] unless the stream ends first (InputStream.readNBytes needs API 33). */
private fun InputStream.readFully(buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) break
        off += n
    }
    return off
}

/** Removes [path]; a directory is emptied first, depth first. */
fun VaultHandle.removeRecursive(path: String) {
    if (stat(path).isDir) {
        list(path).forEach { removeRecursive(childPath(path, it.name)) }
    }
    remove(path)
}
