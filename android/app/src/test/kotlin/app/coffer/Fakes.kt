// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.data.CancelSignal
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.FdOpener
import app.coffer.data.NativeCredentials
import app.coffer.data.NativeVaults
import app.coffer.data.VaultHandle
import app.coffer.data.VaultRef
import app.coffer.data.VaultStore
import app.coffer.data.VolumeInfo
import app.coffer.platform.DocumentAccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

class FakeStore(vararg initial: VaultRef) : VaultStore {
    val state = MutableStateFlow(initial.toList())
    override val vaults = state
    override suspend fun add(uri: String, label: String) =
        VaultRef("id-$label", label, uri).also { state.value += it }
    override suspend fun remove(id: String) { state.value = state.value.filter { it.id != id } }
    override suspend fun rememberPrf(id: String, prf: String) {
        state.value = state.value.map { if (it.id == id) it.copy(lastPrf = prf) else it }
    }
}

/** In-memory volume: a map from path to bytes; directories are paths with children. */
class FakeHandle(
    private val files: Map<String, ByteArray> = emptyMap(),
    private val dirs: Map<String, List<Entry>> = emptyMap(),
) : VaultHandle {
    var locked = false
    override val info = VolumeInfo("AES", "SHA-512", false, false, "exFAT", 1 shl 20)
    private fun check() { if (locked) throw CofferException.Closed() }
    override fun list(path: String): List<Entry> {
        check()
        return dirs[path] ?: throw CofferException.NotADirectory(path)
    }
    override fun stat(path: String): Entry {
        check()
        files[path]?.let { return Entry(path.substringAfterLast('/'), false, it.size.toLong(), null) }
        if (path in dirs) return Entry(path.substringAfterLast('/'), true, 0, null)
        throw CofferException.NotFound(path)
    }
    override fun read(path: String, offset: Long, len: Int): ByteArray {
        check()
        val f = files[path] ?: throw CofferException.NotFound(path)
        if (offset >= f.size) return ByteArray(0)
        return f.copyOfRange(offset.toInt(), minOf(f.size, offset.toInt() + len))
    }
    override fun lock() { locked = true }
}

class FakeNative(
    private val password: String = "secret",
    private val handle: () -> VaultHandle = { FakeHandle() },
) : NativeVaults {
    /** When set, open() blocks until cancel() is called. */
    var blockUntilCancel = false
    var lastCreds: NativeCredentials? = null

    inner class Cancel : CancelSignal {
        val latch = CountDownLatch(1)
        override fun cancel() = latch.countDown()
    }

    override fun newCancel(): CancelSignal = Cancel()

    override fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle {
        lastCreds = creds
        if (blockUntilCancel) {
            (cancel as Cancel).latch.await(10, TimeUnit.SECONDS)
            throw CofferException.Cancelled()
        }
        if (String(creds.password.copyOf(), Charsets.UTF_8) != password) throw CofferException.WrongCredentials()
        return handle()
    }
}

class FakeOpener : FdOpener {
    override fun openReadFd(uri: String) = 3
    override fun openWriteFd(uri: String) = 4
    override fun readPrefix(uri: String, maxBytes: Int) = byteArrayOf(9)
}

class FakeDocuments : DocumentAccess {
    val persisted = mutableListOf<String>()
    var available: Long? = null
    override fun availableBytes(uri: String) = available
    override fun persist(uri: String) { persisted += uri }
    override fun displayName(uri: String) = uri.substringAfterLast('/')
}

/** Writable in-memory volume (case-insensitive, like FAT/exFAT). */
class WritableHandle(
    private val failWritesAfter: Long = Long.MAX_VALUE,
    override val info: VolumeInfo = VolumeInfo("AES", "SHA-512", false, false, "exFAT", 1L shl 30, writable = true),
) : VaultHandle {
    val files = sortedMapOf<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
    val dirs = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "/")
    var locked = false
    var syncs = 0
    private fun check() { if (locked) throw CofferException.Closed() }
    private fun parent(p: String) = p.substringBeforeLast('/').ifEmpty { "/" }
    private fun name(p: String) = p.substringAfterLast('/')
    private fun exists(p: String) = p in files || p in dirs
    override fun list(path: String): List<Entry> {
        check()
        if (path !in dirs) throw CofferException.NotADirectory(path)
        return files.filterKeys { parent(it).equals(path, true) }.map { Entry(name(it.key), false, it.value.size.toLong(), null) } +
            dirs.filter { it != "/" && parent(it).equals(path, true) }.map { Entry(name(it), true, 0, null) }
    }
    override fun stat(path: String): Entry {
        check()
        files[path]?.let { return Entry(name(path), false, it.size.toLong(), null) }
        if (path in dirs) return Entry(name(path), true, 0, null)
        throw CofferException.NotFound(path)
    }
    override fun read(path: String, offset: Long, len: Int): ByteArray {
        check()
        val f = files[path] ?: throw CofferException.NotFound(path)
        if (offset >= f.size) return ByteArray(0)
        return f.copyOfRange(offset.toInt(), minOf(f.size, offset.toInt() + len))
    }
    override fun lock() { locked = true }
    override fun createFile(path: String) {
        check()
        if (exists(path)) throw CofferException.AlreadyExists(path)
        if (parent(path) !in dirs) throw CofferException.NotFound(parent(path))
        files[path] = ByteArray(0)
    }
    override fun mkdir(path: String) {
        check()
        if (exists(path)) throw CofferException.AlreadyExists(path)
        if (parent(path) !in dirs) throw CofferException.NotFound(parent(path))
        dirs += path
    }
    override fun write(path: String, offset: Long, data: ByteArray) {
        check()
        if (offset + data.size > failWritesAfter) throw CofferException.NoSpace()
        val f = files[path] ?: throw CofferException.NotFound(path)
        val out = f.copyOf(maxOf(f.size, (offset + data.size).toInt()))
        data.copyInto(out, offset.toInt())
        files[path] = out
    }
    override fun remove(path: String) {
        check()
        when {
            files.remove(path) != null -> Unit
            path in dirs -> {
                if (list(path).isNotEmpty()) throw CofferException.NotEmpty(path)
                dirs -= path
            }
            else -> throw CofferException.NotFound(path)
        }
    }
    override fun rename(from: String, to: String) {
        check()
        if (!exists(from)) throw CofferException.NotFound(from)
        if (exists(to)) throw CofferException.AlreadyExists(to)
        if (to.startsWith("$from/")) throw CofferException.InvalidPath(to)
        files.remove(from)?.let { files[to] = it; return }
        val moved = dirs.filter { it == from || it.startsWith("$from/") }
        dirs -= moved.toSet()
        dirs += moved.map { to + it.removePrefix(from) }
        files.keys.filter { it.startsWith("$from/") }.forEach { k -> files[to + k.removePrefix(from)] = files.remove(k)!! }
    }
    override fun sync() { syncs++ }
}

class FakeSource(private val docs: Map<String, ByteArray>) : app.coffer.transfer.ImportSource {
    override fun openInput(uri: String) = java.io.ByteArrayInputStream(docs[uri] ?: throw java.io.FileNotFoundException(uri))
    override fun displayName(uri: String) = uri.substringAfterLast('/')
}
