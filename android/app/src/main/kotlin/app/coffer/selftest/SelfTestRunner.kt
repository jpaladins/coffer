// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.selftest

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import app.coffer.data.CofferException
import app.coffer.data.CreateOptions
import app.coffer.data.NativeCredentials
import app.coffer.data.NativeVaults
import app.coffer.data.VaultHandle
import app.coffer.security.AppLockMode
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the device checks need from the app's settings. */
data class AppSecurityState(val screenshotsBlocked: Boolean, val appLock: AppLockMode)

/**
 * Runs the security check: crypto known-answer tests in the native core,
 * a full create/write/reopen cycle on throw-away containers, and the
 * protections of the app and the phone. Emits each result as it is known.
 */
class SelfTestRunner(
    private val context: Context,
    private val native: NativeVaults,
) {
    fun run(state: AppSecurityState): Flow<CheckResult> = flow {
        native.selfTest().forEach { (name, ok) ->
            emit(CheckResult(CheckGroup.Crypto, name, if (ok) CheckStatus.Pass else CheckStatus.Fail))
        }
        for (fs in listOf("exFAT", "FAT")) {
            volumeChecks(fs).forEach { emit(it) }
        }
        deviceChecks(state).forEach { emit(it) }
    }

    private fun volumeChecks(fs: String): List<CheckResult> {
        val dir = File(context.cacheDir, "selftest").apply { mkdirs() }
        val file = File(dir, "check-$fs.hc")
        val out = mutableListOf<CheckResult>()
        fun add(id: String, ok: Boolean) = out.add(CheckResult(CheckGroup.Volume, id, if (ok) CheckStatus.Pass else CheckStatus.Fail, fs))
        val data = ByteArray(300 * 1024).also { SecureRandom().nextBytes(it) }
        try {
            file.delete()
            // Create, write a file, read it back.
            val created = runCatching {
                native.create(fd(file, write = true), creds("check-password", writable = true), options(fs), { _, _ -> }, native.newCancel()).use { h ->
                    h.createFile("/check.bin")
                    h.write("/check.bin", 0, data)
                    h.sync()
                    h.read("/check.bin", 0, data.size).contentEquals(data)
                }
            }.getOrDefault(false)
            add("create_write_read", created)
            // Reopen from disk with the right password: same bytes.
            add("reopen", runCatching {
                native.open(fd(file, write = false), creds("check-password"), native.newCancel()).use { h ->
                    h.read("/check.bin", 0, data.size).contentEquals(data)
                }
            }.getOrDefault(false))
            // A wrong password must be refused, never "opened" with garbage.
            add("wrong_password", runCatching {
                native.open(fd(file, write = false), creds("not-the-password"), native.newCancel()).use { false }
            }.exceptionOrNull() is CofferException.WrongCredentials)
            // Destroy the main header: the backup header must still open the volume.
            RandomAccessFile(file, "rw").use { it.write(ByteArray(512)) }
            add("backup_header", runCatching {
                native.open(fd(file, write = false), creds("check-password", backup = true), native.newCancel()).use { h ->
                    h.info.usedBackupHeader && h.read("/check.bin", 0, data.size).contentEquals(data)
                }
            }.getOrDefault(false))
        } finally {
            data.fill(0)
            file.delete()
        }
        return out
    }

    private fun deviceChecks(state: AppSecurityState): List<CheckResult> {
        val pm = context.packageManager
        val info = context.applicationInfo
        val requested = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
        fun r(id: String, ok: Boolean, warnOnly: Boolean = false) = CheckResult(
            CheckGroup.Device, id,
            when {
                ok -> CheckStatus.Pass
                warnOnly -> CheckStatus.Warn
                else -> CheckStatus.Fail
            },
        )
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return listOf(
            r("no_internet", Manifest.permission.INTERNET !in requested),
            r("no_backup", info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0),
            r("release_build", info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0, warnOnly = true),
            r("screenshots_blocked", state.screenshotsBlocked, warnOnly = true),
            r("screen_lock", keyguard?.isDeviceSecure == true, warnOnly = true),
            r("app_lock", state.appLock != AppLockMode.OFF, warnOnly = true),
            r("hardware_keystore", keystoreInHardware(), warnOnly = true),
        )
    }

    /** A throw-away AES key in the Android Keystore: is it kept in TEE or StrongBox? */
    private fun keystoreInHardware(): Boolean = runCatching {
        val alias = "coffer-selftest"
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        val key = gen.generateKey()
        try {
            val ki = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= 31) {
                ki.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    ki.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                ki.isInsideSecureHardware
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }.getOrDefault(false)

    private fun fd(file: File, write: Boolean): Int {
        val mode = if (write) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, mode).detachFd()
    }

    // PIM 1 keeps the KDF short (16 000 iterations): the check is about correctness, not strength.
    private fun creds(password: String, writable: Boolean = false, backup: Boolean = false) = NativeCredentials(
        password = password.toByteArray(),
        pim = 1,
        keyfiles = emptyList(),
        prf = "SHA-512",
        maxKdfMemory = 256L shl 20,
        tryBackupHeaders = backup,
        writable = writable,
    )

    private fun options(fs: String) = CreateOptions(
        size = 8L shl 20,
        cipher = "AES",
        prf = "SHA-512",
        pim = 1,
        fs = fs,
        quick = true,
        label = "CHECK",
        extraEntropy = ByteArray(0),
    )
}

private inline fun <T> VaultHandle.use(block: (VaultHandle) -> T): T = try {
    block(this)
} finally {
    runCatching { lock() }
}
