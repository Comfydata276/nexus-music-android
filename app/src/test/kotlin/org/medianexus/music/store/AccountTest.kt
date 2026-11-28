package org.medianexus.music.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountTest {
    @get:Rule val folder = TemporaryFolder()

    /** Stands in for the Keystore: reversible, and refuses what it didn't seal. */
    private class FakeBox : SecretBox {
        override fun seal(plain: ByteArray) = byteArrayOf(42) + plain.reversedArray()
        override fun open(sealed: ByteArray) = if (sealed.firstOrNull() == 42.toByte()) sealed.drop(1).reversed().toByteArray() else null
    }

    @Test
    fun anAccountsFolderNameGivesNothingAway() {
        val key = accountKey("https://music.example.org", "ada")
        assertEquals(32, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse("music" in key || "ada" in key)
        // The same account always has the same folder; another account another.
        assertEquals(key, accountKey("https://music.example.org", "ada"))
        assertNotEquals(key, accountKey("https://music.example.org", "grace"))
        assertNotEquals(key, accountKey("http://music.example.org", "ada"))
        // Names that run together differently are told apart.
        assertNotEquals(accountKey("ab", "c"), accountKey("a", "bc"))
        val dirs = accountDirs(File("/files"), File("/cache"), key)
        assertEquals(File("/files/accounts/$key"), dirs.data)
        assertEquals(File("/cache/accounts/$key"), dirs.cache)
    }
}
