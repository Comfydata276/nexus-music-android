package org.medianexus.music.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals with AES-GCM under a key held by the Android Keystore. The key never leaves it (on
 * most phones, never leaves the security chip), so the sealed file is no use copied
 * elsewhere. If the key is lost, as when the phone's lock is reset on some models, what was
 * sealed can't be opened and the app asks for the password again.
 */
class KeystoreBox(private val alias: String = "nexus-music-account") : SecretBox {
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(store: KeyStore): SecretKey? = try {
        store.getKey(alias, null) as? SecretKey
    } catch (e: UnrecoverableKeyException) {
        // AndroidKeyStoreSpi wraps a permanent invalidation without a cause.
        // A failed lookup in the Keystore service carries its cause.
        if (e.cause != null && e.cause !is KeyPermanentlyInvalidatedException) throw e
        null
    }

    private fun newKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** One byte for the length of the nonce, the nonce, then the sealed bytes and tag. */
    override fun seal(plain: ByteArray): ByteArray {
        val store = keyStore()
        val key = existingKey(store) ?: newKey()
        val cipher = try {
            encryptWith(key)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A new sign-in can replace an invalidated key. Unsealing never creates a key.
            store.deleteEntry(alias)
            encryptWith(newKey())
        }
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    private fun encryptWith(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun open(sealed: ByteArray): ByteArray? {
        val ivLength = sealed.firstOrNull()?.toInt() ?: return null
        if (ivLength <= 0 || sealed.size < 1 + ivLength + TAG_BYTES) return null
        return try {
            val key = existingKey(keyStore()) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, sealed, 1, ivLength))
            cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        } catch (_: InvalidAlgorithmParameterException) {
            null
        }
    }

    private companion object {
        const val TAG_BYTES = 16
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
