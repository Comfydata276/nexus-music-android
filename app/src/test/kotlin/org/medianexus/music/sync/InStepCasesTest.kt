package org.medianexus.music.sync

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin that reads, writes and merges the shared record, held to what the Windows app's
 * own code makes of the same inputs. The cases (`instep-cases.json`) were written by
 * `tools/instep` from the Windows source; nothing in them was worked out by hand.
 */
class InStepCasesTest {
    private val cases: JsonObject = Json.parseToJsonElement(javaClass.getResource("/instep-cases.json")!!.readText()).jsonObject

    private fun section(name: String): List<JsonObject> = cases.getValue(name).jsonArray.map { it.jsonObject }

    /** A record as the test's JSON library sees it: the order of a map's entries doesn't count. */
    private fun tree(shared: Shared): JsonElement = Json.parseToJsonElement(SharedText.write(shared))

    private fun record(tree: JsonElement): Shared = SharedText.read(tree.toString()) ?: error("a case's record couldn't be read: $tree")

    private fun plays(tree: JsonElement): List<Play> = tree.jsonArray.map { Play(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.long) }

    private fun playsTree(plays: List<Play>): JsonElement = buildJsonArray { for (play in plays) add(buildJsonArray { add(JsonPrimitive(play.song)); add(JsonPrimitive(play.at)) }) }

    @Test
    fun theLimitsAreWindowsOwn() {
        val limits = cases.getValue("limits").jsonObject
        assertEquals(limits.getValue("version").jsonPrimitive.long, LAYOUT)
        assertEquals(limits.getValue("history_length").jsonPrimitive.long, HISTORY_LENGTH.toLong())
        assertEquals(limits.getValue("plays_shared").jsonPrimitive.long, PLAYS_SHARED.toLong())
        assertEquals(limits.getValue("queue_behind").jsonPrimitive.long, QUEUE_BEHIND.toLong())
        assertEquals(limits.getValue("queue_ahead").jsonPrimitive.long, QUEUE_AHEAD.toLong())
        assertEquals(limits.getValue("long_ago").jsonPrimitive.long, LONG_AGO)
        assertEquals(limits.getValue("keep_removed").jsonPrimitive.long, KEEP_REMOVED)
        assertEquals(limits.getValue("playlist_name").jsonPrimitive.content, PLAYLIST_NAME)
    }

    @Test
    fun aTextIsReadAsWindowsReadsItOrNotAtAll() {
        val cases = section("reads")
        assertTrue(cases.size > 80)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val read = SharedText.read(case.getValue("text").jsonPrimitive.content)
            val wanted = case.getValue("record")
            if (wanted is JsonNull) {
                assertNull("Windows can't read this, and the phone read it: $name", read)
            } else {
                assertNotNull("Windows reads this, and the phone couldn't: $name", read)
                assertEquals(name, wanted, tree(read!!.copy(unknown = emptyList())))
            }
        }
    }

    @Test
    fun aRecordIsWrittenAsWindowsWritesItByteForByte() {
        val cases = section("writes")
        assertTrue(cases.size >= 6)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val text = case.getValue("text").jsonPrimitive.content
            assertEquals(name, text, SharedText.write(record(case.getValue("record"))))
            // And the text Windows wrote reads back to the record it was written from.
            assertEquals(name, case.getValue("record"), tree(SharedText.read(text)!!))
        }
    }

    @Test
    fun twoRecordsMergeAsWindowsMergesThem() {
        val cases = section("merges")
        assertTrue(cases.size > 45)
        val wrote = ArrayList<JsonObject>()
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val now = case.getValue("now").jsonPrimitive.long
            val (a, b) = record(case.getValue("a")) to record(case.getValue("b"))
            val merged = Merge.merge(a, b, now)
            assertEquals(name, case.getValue("merged"), tree(merged))
            assertEquals("$name, the other way round", case.getValue("merged_other_way"), tree(Merge.merge(b, a, now)))
            assertEquals("$name: whether it is written back", case.getValue("writes_back").jsonPrimitive.boolean, merged != b)
            // The same through the one function the app uses.
            val settled = KotlinSettler.settle(server = SharedText.write(b), local = SharedText.write(a), now = now)
            // (A server's record in a later layout is never merged at all.)
            if (b.layout > LAYOUT) {
                assertTrue(name, settled is Settled.Newer)
                continue
            }
            settled as Settled.Merged
            assertEquals(name, case.getValue("merged"), Json.parseToJsonElement(settled.text))
            assertEquals(name, case.getValue("writes_back").jsonPrimitive.boolean, settled.write)
            wrote += buildJsonObject {
                put("name", JsonPrimitive(name))
                put("text", JsonPrimitive(settled.text))
                put("record", case.getValue("merged"))
            }
        }
        // For the check the other way (`tools/instep/run reads app/build/instep/kotlin-wrote.json`):
        // that Windows' code reads each of these texts back to the record Windows itself made.
        File("build/instep").mkdirs()
        File("build/instep/kotlin-wrote.json").writeText(JsonArray(wrote).toString())
    }

    @Test
    fun historyAndPlaysMergeAsWindowsMergesThem() {
        val cases = section("plays")
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val (a, b) = plays(case.getValue("a")) to plays(case.getValue("b"))
            assertEquals(name, case.getValue("history"), playsTree(Merge.mergeHistory(a, b)))
            assertEquals(name, case.getValue("history_other_way"), playsTree(Merge.mergeHistory(b, a)))
            assertEquals(name, case.getValue("plays"), playsTree(Merge.mergePlays(a, b)))
            assertEquals(name, case.getValue("plays_other_way"), playsTree(Merge.mergePlays(b, a)))
        }
    }

    @Test
    fun notesMergeAsWindowsMergesThem() {
        val cases = section("notes")
        assertTrue(cases.size >= 9)
        fun notes(tree: JsonElement) = record(buildJsonObject { put("added", tree) }).added
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val now = case.getValue("now").jsonPrimitive.long
            val (a, b) = notes(case.getValue("a")) to notes(case.getValue("b"))
            assertEquals(name, case.getValue("merged"), tree(Shared(added = Merge.mergeNotes(a, b, now))).jsonObject.getValue("added"))
            assertEquals(name, case.getValue("merged_other_way"), tree(Shared(added = Merge.mergeNotes(b, a, now))).jsonObject.getValue("added"))
        }
    }

    @Test
    fun textComparesByBytesAsRustDoes() {
        // By UTF-16 units U+10000 (a pair beginning D800) sorts before U+FFFF; by bytes, after.
        assertTrue("𐀀" < "￿")
        assertTrue(SharedText.bytewise("𐀀", "￿") > 0)
        assertTrue(SharedText.bytewise("a", "ab") < 0)
        assertEquals(0, SharedText.bytewise("café", "café"))
        assertTrue(SharedText.bytewise("Z", "a") < 0)
    }
}
