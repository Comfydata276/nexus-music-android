package org.medianexus.music.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
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
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    override fun open(sealed: ByteArray): ByteArray? = try {
        val ivLength = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, ivLength))
        cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.security.ProviderException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
