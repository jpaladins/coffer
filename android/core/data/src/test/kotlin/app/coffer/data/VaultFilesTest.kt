// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Writable in-memory volume with case-insensitive lookup, like FAT/exFAT. */
class MemHandle(private val failWriteAt: Long = -1) : VaultHandle {
    val files = sortedMapOf<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
    val dirs = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "/")
    val writes = mutableListOf<Int>()
    var synced = 0
    override val info = VolumeInfo("AES", "SHA-512", false, false, "exFAT", 1L shl 30, writable = true)
    private fun parent(p: String) = p.substringBeforeLast('/').ifEmpty { "/" }
    private fun name(p: String) = p.substringAfterLast('/')
    override fun list(path: String) = (files.keys.filter { parent(it).equals(path, true) }.map { Entry(name(it), false, files.getValue(it).size.toLong(), null) } +
        dirs.filter { it != "/" && parent(it).equals(path, true) }.map { Entry(name(it), true, 0, null) })
    override fun stat(path: String): Entry = files[path]?.let { Entry(name(path), false, it.size.toLong(), null) }
        ?: if (path in dirs) Entry(name(path), true, 0, null) else throw CofferException.NotFound(path)
    override fun read(path: String, offset: Long, len: Int) = ByteArray(0)
    override fun lock() {}
    override fun createFile(path: String) {
        if (path in files || path in dirs) throw CofferException.AlreadyExists(path)
        files[path] = ByteArray(0)
    }
    override fun mkdir(path: String) {
        if (path in files || path in dirs) throw CofferException.AlreadyExists(path)
        dirs += path
    }
    override fun write(path: String, offset: Long, data: ByteArray) {
        if (failWriteAt >= 0 && offset >= failWriteAt) throw CofferException.NoSpace()
        val f = files[path] ?: throw CofferException.NotFound(path)
        val out = f.copyOf(maxOf(f.size, (offset + data.size).toInt()))
        data.copyInto(out, offset.toInt())
        files[path] = out
        writes += data.size
    }
    override fun remove(path: String) {
        when {
            files.remove(path) != null -> {}
            path in dirs -> {
                if (list(path).isNotEmpty()) throw CofferException.NotEmpty(path)
                dirs -= path
            }
            else -> throw CofferException.NotFound(path)
        }
    }
    override fun sync() { synced++ }
}

class VaultFilesTest {
    private fun data(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    @Test
    fun import_writes_in_chunks_and_syncs() {
        val h = MemHandle()
        val src = data(IMPORT_CHUNK * 2 + 123)
        val progress = mutableListOf<Long>()
        val n = h.importStream("/a.bin", ByteArrayInputStream(src), onProgress = { progress += it })
        assertEquals(src.size.toLong(), n)
        assertArrayEquals(src, h.files["/a.bin"])
        assertEquals(listOf(IMPORT_CHUNK, IMPORT_CHUNK, 123), h.writes)
        assertEquals(src.size.toLong(), progress.last())
        assertEquals(1, h.synced)
    }

    /** A slow stream that returns a few bytes per read must still fill whole chunks. */
    @Test
    fun import_fills_chunks_from_short_reads() {
        val h = MemHandle()
        val src = data(IMPORT_CHUNK + 10)
        val trickle = object : InputStream() {
            var pos = 0
            override fun read() = if (pos < src.size) src[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= src.size) return -1
                val n = minOf(len, 1000, src.size - pos)
                src.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        h.importStream("/t.bin", trickle)
        assertArrayEquals(src, h.files["/t.bin"])
        assertEquals(listOf(IMPORT_CHUNK, 10), h.writes)
    }

    @Test
    fun import_failure_removes_partial_file() {
        val h = MemHandle(failWriteAt = IMPORT_CHUNK.toLong())
        try {
            h.importStream("/big.bin", ByteArrayInputStream(data(IMPORT_CHUNK * 3)))
            fail("expected NoSpace")
        } catch (_: CofferException.NoSpace) {
        }
        assertFalse(h.exists("/big.bin"))
    }

    @Test
    fun import_source_error_removes_partial_file() {
        val h = MemHandle()
        val broken = object : InputStream() {
            override fun read() = throw IOException("source gone")
        }
        try {
            h.importStream("/x.bin", broken)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertFalse(h.exists("/x.bin"))
    }

    @Test
    fun import_cancel_removes_partial_file() {
        val h = MemHandle()
        var chunks = 0
        try {
            h.importStream("/c.bin", ByteArrayInputStream(data(IMPORT_CHUNK * 3)), isActive = { chunks++ < 1 })
            fail("expected Cancelled")
        } catch (_: CofferException.Cancelled) {
        }
        assertFalse(h.exists("/c.bin"))
    }

    @Test
    fun unique_name_adds_counter_before_extension() {
        val h = MemHandle()
        assertEquals("foto.jpg", h.uniqueName("/", "foto.jpg"))
        h.createFile("/foto.jpg")
        assertEquals("FOTO (1).JPG", h.uniqueName("/", "FOTO.JPG"))
        h.createFile("/foto (1).jpg")
        assertEquals("foto (2).jpg", h.uniqueName("/", "foto.jpg"))
        h.mkdir("/doc")
        h.createFile("/doc/.profile")
        assertEquals(".profile (1)", h.uniqueName("/doc", ".profile"))
        assertEquals("doc (1)", h.uniqueName("/", "doc"))
    }

    @Test
    fun remove_recursive_empties_directories() {
        val h = MemHandle()
        h.mkdir("/a")
        h.mkdir("/a/b")
        h.createFile("/a/b/f")
        h.createFile("/a/g")
        h.removeRecursive("/a")
        assertTrue(h.files.isEmpty())
        assertEquals(setOf("/"), h.dirs.toSet())
    }

    @Test
    fun read_only_handle_rejects_writes() {
        val ro = FakeHandle(VolumeInfo("AES", "SHA-512", false, false, "FAT32", 1))
        try {
            ro.createFile("/x")
            fail("expected ReadOnly")
        } catch (_: CofferException.ReadOnly) {
        }
    }
}
