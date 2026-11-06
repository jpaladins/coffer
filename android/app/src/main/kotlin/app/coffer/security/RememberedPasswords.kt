// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/** The biometric key changed (new fingerprint enrolled): the saved password is gone. */
class KeyInvalidatedException : Exception("biometric key invalidated")

/**
 * "Remember password with fingerprint" (off by default). The password is
 * encrypted with an Android Keystore AES-GCM key that can only be used
 * after a strong biometric authentication, and is invalidated when a new
 * fingerprint is enrolled. Ciphers returned here must be passed to
 * BiometricPrompt.CryptoObject before use.
 */
interface RememberedPasswords {
    fun isAvailable(): Boolean
    suspend fun has(vaultId: String): Boolean
    /** @throws KeyInvalidatedException */
    fun encrypter(vaultId: String): Cipher
    /** Null when nothing is saved. @throws KeyInvalidatedException */
    suspend fun decrypter(vaultId: String): Cipher?
    /** [password] is not wiped (the caller still unlocks with it). */
    suspend fun save(vaultId: String, cipher: Cipher, password: CharArray)
    suspend fun recall(vaultId: String, cipher: Cipher): CharArray
    suspend fun forget(vaultId: String)
}

object NoRememberedPasswords : RememberedPasswords {
    override fun isAvailable() = false
    override suspend fun has(vaultId: String) = false
    override fun encrypter(vaultId: String): Cipher = throw KeyInvalidatedException()
    override suspend fun decrypter(vaultId: String): Cipher? = null
    override suspend fun save(vaultId: String, cipher: Cipher, password: CharArray) {}
    override suspend fun recall(vaultId: String, cipher: Cipher) = CharArray(0)
    override suspend fun forget(vaultId: String) {}
}

private const val KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

class KeystoreRememberedPasswords(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : RememberedPasswords {
    private fun alias(id: String) = "coffer_pw_$id"
    private fun prefKey(id: String) = stringPreferencesKey("bio_pw_$id")

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun has(vaultId: String) = dataStore.data.first()[prefKey(vaultId)] != null

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(id: String, create: Boolean): SecretKey? {
        (keyStore().getKey(alias(id), null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun encrypter(vaultId: String): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(vaultId, create = true)) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        keyStore().deleteEntry(alias(vaultId))
        throw KeyInvalidatedException()
    }

    override suspend fun decrypter(vaultId: String): Cipher? {
        val stored = dataStore.data.first()[prefKey(vaultId)] ?: return null
        val iv = Base64.decode(stored.substringBefore(':'), Base64.NO_WRAP)
        val key = key(vaultId, create = false) ?: run {
            forget(vaultId)
            throw KeyInvalidatedException()
        }
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget(vaultId)
            throw KeyInvalidatedException()
        }
    }

    override suspend fun save(vaultId: String, cipher: Cipher, password: CharArray) {
        val buf = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val plain = ByteArray(buf.remaining()).also { buf.get(it) }
        if (buf.hasArray()) buf.array().fill(0)
        try {
            val ct = cipher.doFinal(plain)
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            dataStore.edit { it[prefKey(vaultId)] = value }
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun recall(vaultId: String, cipher: Cipher): CharArray {
        val stored = dataStore.data.first()[prefKey(vaultId)] ?: return CharArray(0)
        val plain = cipher.doFinal(Base64.decode(stored.substringAfter(':'), Base64.NO_WRAP))
        try {
            val chars = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain))
            return CharArray(chars.remaining()).also { chars.get(it) }.also { if (chars.hasArray()) chars.array().fill('\u0000') }
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun forget(vaultId: String) {
        dataStore.edit { it.remove(prefKey(vaultId)) }
        runCatching { keyStore().deleteEntry(alias(vaultId)) }
    }
}
