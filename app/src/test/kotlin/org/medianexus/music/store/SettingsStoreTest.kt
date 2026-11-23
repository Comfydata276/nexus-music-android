package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** Ported from `a_damaged_settings_file_is_never_quietly_replaced` in src/config.rs. */
    @Test
    fun aDamagedSettingsFileIsNeverQuietlyReplaced() {
        val dir = folder.newFolder()
        val path = File(dir, "settings.json")
        // A first run: defaults, and nothing to say.
        var (settings, damage) = SettingsStore.loadChecked(path)
        assertNull(damage)
        assertTrue(settings.server.isEmpty())
        // A good file is read, and a copy of it kept.
        SettingsStore.save(path, Settings(server = "https://music.example.org", cacheAhead = 5))
        SettingsStore.loadChecked(path).let { (loaded, note) -> settings = loaded; damage = note }
        assertNull(damage)
        assertEquals("https://music.example.org", settings.server)
        assertTrue(File(dir, "settings.backup.json").exists())
        // Cut short: the copy stands in, the file is mended, and the app says so.
        path.writeText("""{"server": "https://music.ex""")
        SettingsStore.loadChecked(path).let { (loaded, note) -> settings = loaded; damage = note }
        assertEquals("https://music.example.org", settings.server)
        assertEquals(5, settings.cacheAhead)
        assertEquals(SettingsDamage.UsedBackup, damage)
        assertEquals("https://music.example.org", SettingsStore.loadChecked(path).first.server)
        // No copy either: the damaged file is kept under another name, not overwritten.
        path.writeText("not settings at all")
        assertTrue(File(dir, "settings.backup.json").delete())
        SettingsStore.loadChecked(path).let { (loaded, note) -> settings = loaded; damage = note }
        assertTrue(settings.server.isEmpty())
        assertEquals(SettingsDamage.StartedAfresh("settings.damaged.json"), damage)
        assertArrayEquals("not settings at all".toByteArray(), File(dir, "settings.damaged.json").readBytes())
        assertFalse(path.exists())
    }

    @Test
    fun aFileOfTheWrongShapeCountsAsDamaged() {
        val dir = folder.newFolder()
        val path = File(dir, "settings.json")
        SettingsStore.save(path, Settings(theme = "Ocean"))
        SettingsStore.loadChecked(path)
        // Valid JSON, but a setting of the wrong kind: the backup stands in.
        path.writeText("""{"theme": 7}""")
        val (settings, damage) = SettingsStore.loadChecked(path)
        assertEquals("Ocean", settings.theme)
        assertEquals(SettingsDamage.UsedBackup, damage)
    }

    @Test
    fun readsAFileFromAnOlderVersionAndIgnoresUnknownSettings() {
        val path = File(folder.newFolder(), "settings.json")
        // As the Windows app names things, with one this app doesn't have and most missing.
        path.writeText("""{"server":"http://nas:4533","cache_limit_mb":5120,"close_to_tray":true,"lists":{"album:1":{"shuffle":true,"sort_chosen":true}}}""")
        val (settings, damage) = SettingsStore.loadChecked(path)
        assertNull(damage)
        assertEquals("http://nas:4533", settings.server)
        assertEquals(5120, settings.cacheLimitMb)
        assertEquals(ListPrefs(shuffle = true, sortChosen = true), settings.lists["album:1"])
        assertTrue(settings.gapless)
        assertEquals(DEFAULT_THEME, settings.theme)
    }

    @Test
    fun writesAtomicallyAndLeavesNoTemporaryFile() {
        val dir = folder.newFolder()
        val path = File(dir, "settings.json")
        SettingsStore.save(path, Settings(username = "first"))
        SettingsStore.save(path, Settings(username = "second"))
        assertEquals("second", SettingsStore.read(path)!!.username)
        assertFalse(File(dir, "settings.tmp").exists())
        // Written with the Windows app's field names.
        assertTrue(path.readText().contains("\"cache_limit_mb\": 2048"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesShowAtOnceAndReachTheDiskOnceTheWriterRuns() = runTest {
        val path = File(folder.newFolder(), "settings.json")
        val io = StandardTestDispatcher(testScheduler)
        val scope = TestScope(io)
        val store = SettingsStore.open(path, scope, io)
        assertNull(store.damage)
        store.update { it.copy(theme = "Ember") }
        store.update { it.copy(scrobble = false) }
        // In memory straight away; not yet on disk.
        assertEquals("Ember", store.value.theme)
        assertFalse(path.exists())
        scope.advanceUntilIdle()
        store.flush()
        val saved = SettingsStore.read(path)!!
        assertEquals("Ember", saved.theme)
        assertFalse(saved.scrobble)
        // A change that changes nothing isn't written.
        val written = path.lastModified()
        store.update { it }
        scope.advanceUntilIdle()
        assertEquals(written, path.lastModified())
        scope.cancel()
    }

    /** Ported from `names_the_equaliser_curve_and_works_out_the_preamp` in src/config.rs. */
    @Test
    fun namesTheEqualiserCurveAndWorksOutThePreamp() {
        var settings = Settings()
        assertEquals(EqCurve.BuiltIn(0), settings.eqPreset())
        settings = settings.copy(eqGains = listOf(1.5f, 0f, -2f, 0f, 4.5f, 3f))
        assertEquals(EqCurve.Custom, settings.eqPreset())
        settings = settings.copy(eqSaved = listOf(SavedCurve("Headphones", settings.eqGains)))
        assertEquals(EqCurve.Saved("Headphones"), settings.eqPreset())
        // Off, a set amount, and Auto: as much as the band raised the most.
        assertEquals(0f, settings.eqPreampNow(), 0f)
        settings = settings.copy(eqPreamp = -3f)
        assertEquals(-3f, settings.eqPreampNow(), 0f)
        settings = settings.copy(eqPreamp = 1f)
        assertEquals(-4.5f, settings.eqPreampNow(), 0f)
        settings = settings.copy(eqGains = List(6) { -3f })
        assertEquals(0f, settings.eqPreampNow(), 0f)
    }
}
