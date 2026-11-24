package org.medianexus.music.playback

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.medianexus.music.graph

/**
 * A music server for the tests that run the playback service whole: it answers like
 * Navidrome just enough for someone to be signed in (every request is answered "ok", with
 * nothing in it, unless a test says otherwise), and remembers what it was asked.
 */
class StandInServer {
    private val server = MockWebServer()

    /** What was asked, as "endpoint id" ("star s3"), in order. */
    val asked = CopyOnWriteArrayList<String>()

    /** Endpoints the server refuses, with its own words for why. */
    val refuses = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** What an endpoint answers with, inside the response, when it has something to say. */
    val answers = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
                asked += listOfNotNull(endpoint, request.url.queryParameter("id")).joinToString(" ")
                val refusal = refuses[endpoint]
                val body = if (refusal != null) {
                    """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"$refusal"}}}"""
                } else {
                    val more = answers[endpoint]?.let { ",$it" }.orEmpty()
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"$more}}"""
                }
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
    }

    fun stop() = server.close()

    val address: String get() = server.url("/").toString()

    /**
     * Signs the app in to this server, and returns once the account's objects are there.
     * (Waited for here, before a test begins: it is other threads' work.) The objects of
     * this sign-in, not the last one's: signed out and in again as the same person, the
     * accounts may not yet have let the old ones go, and are briefly with nobody after.
     */
    fun signIn(app: Application, username: String = "tester") {
        app.graph.session.signIn(address, username, "a password")
        runBlocking {
            withTimeout(30_000) {
                val signedIn = app.graph.session.state.first { it is org.medianexus.music.SessionState.SignedIn && it.account.username == username } as org.medianexus.music.SessionState.SignedIn
                app.graph.accounts.state.first { it?.api === signedIn.api }
            }
        }
    }
}
