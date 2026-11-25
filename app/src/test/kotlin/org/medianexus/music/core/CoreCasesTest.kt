package org.medianexus.music.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.sync.PLAYLIST_NAME
import org.medianexus.music.sync.PhoneShared

/**
 * The phone and the Windows app run the same rules, case by case.
 *
 * `instep-cases.json` is written by `tools/instep`, which is the Windows app's own library
 * compiled from the Windows checkout beside this repository: for each of a list of awkward
 * inputs, what that library decides. Here the same inputs go through the phone's door
 * ([Core]) to the library the phone carries, and the answers must be the same. So nothing is
 * lost or changed on the way through the door, in either direction; and the library the
 * phone has pinned decides as the Windows checkout does. (When this fails after the Windows
 * library has changed: move the pin, see docs/DESIGN.md, "The shared core".)
 */
class CoreCasesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cases = json.parseToJsonElement(javaClass.getResource("/instep-cases.json")!!.readText()).jsonObject

    private val plays = ListSerializer(Play.serializer())
    private val sources = ListSerializer(PlayFrom.serializer())
    private val notes = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Note.serializer()))

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonElement.orNull() = takeIf { it !is JsonNull }

    @Test
    fun whatThePhoneKnowsByNameIsTheLibrarysOwn() {
        val names = cases.getValue("names").jsonObject
        val mine = Core.names()
        assertEquals(names.text("playlist"), mine.playlist)
        assertEquals(names.text("reserved"), mine.reserved)
        assertEquals(names.getValue("history_length").jsonPrimitive.int, mine.historyLength)
        assertEquals(names.getValue("plays_shared").jsonPrimitive.int, mine.playsShared)
        // And the two the app keeps as constants of its own are those.
        assertEquals(mine.playlist, PLAYLIST_NAME)
        assertEquals(mine.playsShared, PhoneShared.PLAYS_SHARED)
        assertEquals(mine.historyLength, org.medianexus.music.store.RecentlyPlayed.HISTORY_LENGTH)
        assertTrue(Core.reserved("  $PLAYLIST_NAME ") && !Core.reserved("$PLAYLIST_NAME 2"))
    }

    @Test
    fun anExchangeIsDecidedHereAsTheWindowsLibraryDecidesIt() {
        val settles = cases.getValue("settles").jsonArray
        assertTrue("the cases are there", settles.size > 100)
        for (case in settles) {
            val name = case.jsonObject.text("name")
            val d = case.jsonObject.getValue("device").jsonObject
            val a = case.jsonObject.getValue("answers").jsonObject
            val device = Device(
                username = d.text("username"), account = d.text("account"), choices = d.getValue("choices"),
                notes = json.decodeFromJsonElement(notes, d.getValue("notes")),
                history = json.decodeFromJsonElement(plays, d.getValue("history")),
                plays = json.decodeFromJsonElement(plays, d.getValue("plays")),
                playSources = json.decodeFromJsonElement(sources, d.getValue("play_sources")),
            )
            val answers = Answers(
                listed = json.decodeFromJsonElement(ListSerializer(Stated.serializer()), a.getValue("listed")),
                playlist = a.getValue("playlist").orNull()?.let { json.decodeFromJsonElement(Stated.serializer(), it) },
                date = a.getValue("date").jsonPrimitive.longOrNull,
                received = a.getValue("received").jsonPrimitive.long,
            )
            val got = Core.settle(device, answers)
            // The library's own answer: "Stop" with why, or "Go" with the plan.
            val want = case.jsonObject.getValue("outcome").jsonObject
            val stop = want["Stop"]
            if (stop != null) {
                assertEquals(name, stop.jsonPrimitive.content, got.stop?.kind)
                assertNull(name, got.plan)
                assertTrue(name, got.stop!!.words.isNotEmpty())
                continue
            }
            val plan = want.getValue("Go").jsonObject
            val mine = got.plan ?: throw AssertionError("$name: stopped (${got.stop}) where the Windows library goes on")
            assertNull(name, got.stop)
            assertEquals(name, json.decodeFromJsonElement(plays, plan.getValue("keep").jsonObject.getValue("plays")), mine.keepPlays)
            assertEquals(name, json.decodeFromJsonElement(sources, plan.getValue("keep").jsonObject.getValue("sources")), mine.keepSources)
            // What is handed back to the library unread is what the library itself wrote.
            assertEquals(name, plan.getValue("take"), mine.take)
            assertEquals(name, plan.getValue("write"), mine.write ?: JsonNull)
            // And what the phone reads of the plan is what is in it.
            val take = plan.getValue("take").jsonObject
            assertEquals(name, json.decodeFromJsonElement(notes, take.getValue("notes")), mine.notes)
            assertEquals(name, json.decodeFromJsonElement(plays, take.getValue("history")), mine.history)
            assertEquals(name, plan.getValue("held").orNull()?.jsonPrimitive?.content, mine.held?.kind)
            plan.getValue("write").orNull()?.jsonObject?.let { write ->
                assertEquals(name, write.getValue("into").orNull()?.jsonPrimitive?.content, mine.writeInto)
                assertEquals(name, write.text("text"), mine.writeText)
                assertEquals(name, write.text("found"), mine.writeFound)
                // The whole of a write, carried out as the phone carries it out: tried,
                // then taken by the server, leaves the choices the library says it does.
                val sending = Core.sending(Core.take(device.choices, mine.take), mine.write!!)
                assertTrue(name, sending.fits)
                assertEquals(name, write.getValue("agreed"), Core.written(sending.choices, mine.write!!).jsonObject.getValue("agreed"))
            }
            // The clock, as the library has it in the plan (the server's minus the device's).
            val offset = plan.getValue("offset").jsonPrimitive.longOrNull
            assertEquals(name, offset != null, got.clock.measured)
            assertEquals(name, offset != null && kotlin.math.abs(offset) > 300, got.clock.wrong)
        }
    }

    /** A change as the library writes one down, as the phone names it. */
    private fun change(written: JsonElement): Change {
        val (kind, value) = written.jsonObject.entries.single()
        fun field(name: String) = value.jsonObject.getValue(name).jsonPrimitive
        return when (kind) {
            "Pin" -> Change.Pin(value.jsonPrimitive.content)
            "Unpin" -> Change.Unpin(value.jsonPrimitive.content)
            "Shuffle" -> Change.Shuffle(field("key").content, field("on").boolean)
            "Sort" -> Change.Sort(field("key").content, field("sort").int, field("descending").boolean)
            "Usual" -> Change.Usual(field("key").content)
            "Theme" -> Change.Theme(value.jsonPrimitive.content)
            else -> throw AssertionError("a change the phone has no name for: $kind")
        }
    }

    /** What is shown, as the library writes it down, beside what the phone was told. */
    private fun assertShown(name: String, want: JsonObject, got: Shown) {
        assertEquals(name, want.getValue("pins").jsonArray.map { it.jsonPrimitive.content }, got.pins)
        assertEquals(name, want.text("theme"), got.theme)
        val lists = want.getValue("lists").jsonObject
        assertEquals(name, lists.keys, got.lists.keys)
        for ((key, prefs) in lists) {
            val p = prefs.jsonObject
            val mine = got.lists.getValue(key)
            assertEquals("$name: $key", listOf<JsonElement>(JsonPrimitive(mine.shuffle), JsonPrimitive(mine.sort), JsonPrimitive(mine.descending), JsonPrimitive(mine.sortChosen), JsonPrimitive(mine.at)),
                listOf(p.getValue("shuffle"), p.getValue("sort"), p.getValue("descending"), p.getValue("sort_chosen"), p.getValue("at")))
        }
    }

    @Test
    fun aRunOfThePersonsChangesIsKeptAndShownHereAsTheWindowsLibraryKeepsAndShowsIt() {
        val runs = cases.getValue("changes").jsonArray
        assertTrue(runs.size >= 8)
        for (run in runs) {
            val name = run.jsonObject.text("name")
            val account = run.jsonObject.text("account")
            var choices = run.jsonObject.getValue("from")
            for ((at, step) in run.jsonObject.getValue("steps").jsonArray.withIndex()) {
                val s = step.jsonObject
                val out = Core.change(choices, account, change(s.getValue("change")))
                assertEquals("$name, step $at", s.getValue("changed").jsonPrimitive.boolean, out.changed)
                assertEquals("$name, step $at", s.getValue("choices"), out.choices)
                assertShown("$name, step $at", s.getValue("shown").jsonObject, Core.shown(out.choices, account))
                // Under any other account none of it shows: it waits for its own.
                assertShown("$name, step $at, another account", s.getValue("shown_to_another_account").jsonObject, Core.shown(out.choices, "another"))
                choices = out.choices
            }
        }
    }
}
