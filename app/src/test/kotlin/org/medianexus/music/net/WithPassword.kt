package org.medianexus.music.net

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * **For the tests only**: a client for a music server made from a password, as the Nexus server
 * makes the salt and token it hands a phone (the MD5 of the password and the salt). The app
 * itself never has a password to make one from (`MusicLogin`).
 */
fun Subsonic(
    server: String,
    username: String,
    password: String,
    http: OkHttpClient,
    offline: OfflineSwitch = OfflineSwitch(),
    salt: String = "abcdefghijklmnop",
    io: CoroutineDispatcher = Dispatchers.IO,
): Subsonic = Subsonic(server, username, MusicLogin(Handed(salt, md5Hex(password + salt), "1")), http, offline, io)

/** The token for a password and a salt, as Navidrome checks it. */
fun md5Hex(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
