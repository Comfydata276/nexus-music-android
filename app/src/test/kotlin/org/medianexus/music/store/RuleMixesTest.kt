package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.library.MixRules

class RuleMixesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun mixes(file: File) = RuleMixes(file, Dispatchers.IO).also { runBlocking { it.open() } }

    @Test
    fun keptPerAccountInWindowsForm() = runBlocking {
        val file = File(folder.root, "rule-mixes.json")
        val mixes = mixes(file)
        // Made as Windows makes one: named, its id the time, every rule at its start but the
        // length (50 songs).
        val made = mixes.create("  Old rock ", now = 1_700_000_000_123)
        assertEquals(MixRules(id = 1_700_000_000_123, name = "Old rock", size = 1), made)
        mixes.update(made.id) { it.copy(genre = "rock", fromYear = 1970, toYear = 1979, played = 2, order = 1) }
        // Windows' own field names, so the file reads as its `Rules`.
        val text = file.readText()
        assertTrue(text, "\"from_year\":1970" in text && "\"to_year\":1979" in text && "\"id\":1700000000123" in text)
        assertEquals(listOf(MixRules(id = 1_700_000_000_123, name = "Old rock", genre = "rock", fromYear = 1970, toYear = 1979, played = 2, order = 1, size = 1)), mixes(file).state.value)
        mixes.create("Second", now = 5)
        mixes.delete(made.id)
        assertEquals(listOf("Second"), mixes(file).state.value.map { it.name })
        // A file as Windows writes it, with fields left out, reads with their starting values.
        file.writeText("""[{"id":9,"name":"From Windows","liked":true}]""")
        assertEquals(listOf(MixRules(id = 9, name = "From Windows", liked = true)), mixes(file).state.value)
    }

    @Test
    fun aFileThatCantBeReadIsSetAsideNotWrittenOver() = runBlocking {
        val file = File(folder.root, "rule-mixes.json")
        file.writeText("not json")
        val mixes = mixes(file)
        assertEquals(emptyList<MixRules>(), mixes.state.value)
        assertEquals("not json", File(folder.root, "rule-mixes.damaged.json").readText())
    }
}
