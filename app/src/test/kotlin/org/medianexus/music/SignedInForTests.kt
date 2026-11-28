package org.medianexus.music

import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.net.Handed
import org.medianexus.music.net.md5Hex
import org.medianexus.music.nexus.KeptAccount
import org.medianexus.music.nexus.KeptMusic
import org.medianexus.music.nexus.NexusKept

/**
 * **For the tests that are not about the Nexus server**: signs the phone in to a music server as
 * the Nexus server's machine would leave it signed in (what it keeps, and the music login it
 * hands over: a salt and the token for it), without a backend. The tests of signing in itself
 * go through the machine and a stand-in backend (`nexus/NeverWaitsTest`, `nexus/SignInTest`).
 */
fun Session.signIn(address: String, username: String, password: String) {
    val salt = TEST_SALT
    val handed = Handed(salt, md5Hex(password + salt), "1")
    val email = "${username.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "someone" }}@example.org"
    // (The account's number: one for each name, as a backend gives every account its own.)
    val number = (username.hashCode().toLong() and 0xffffff) + 1
    val who = KeptAccount(TEST_NEXUS, TEST_BACKEND, number.toString())
    val music: JsonObject = buildJsonObject { put("address", address.trimEnd('/')); put("name", username); put("salt", salt); put("token", handed.token); put("count", 1) }
    // What the machine keeps at a sign-in (`client::Saved`), in its own shape.
    val saved: JsonObject = buildJsonObject {
        put("nexus", TEST_NEXUS); put("backend_id", TEST_BACKEND); put("account", number); put("email", email); put("name", username)
        put("device", 1); put("restore", 1); put("life", 1); put("change", 1); put("session", "ab".repeat(32)); put("music", music)
    }
    keptForTests.save(NexusKept.Parts(saved, emptyList()))
    handed(KeptMusic(address, username, handed), who)
}

/** Signs out, as the machine does when the person presses Sign out: what is kept forgotten, and
 *  the account with it. Done when the returned job is. */
fun Session.signOut(): Deferred<Unit> {
    keptForTests.save(NexusKept.Parts.NONE)
    return forgotten()
}

const val TEST_NEXUS = "https://nexus.example.org"

/** A salt as the backend makes one: 32 bytes, as 64 lower-case hex. */
const val TEST_SALT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val TEST_BACKEND = "0123456789abcdef0123456789abcdef"
