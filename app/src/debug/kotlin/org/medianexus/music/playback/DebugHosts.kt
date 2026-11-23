package org.medianexus.music.playback

import android.content.Context
import java.io.File

/**
 * Debug builds only: callers the test rig has asked to be treated as known hosts, each by
 * package name and the SHA-256 of its signing certificate, as Google's list names Android
 * Auto. The rig's car probe is given no notification access and is added here (see
 * `scripts/carcheck --known-host`), which proves end to end that a caller Android does not
 * trust is let in for its certificate and for nothing else.
 *
 * Kept in a file, so it is still there when the app is started cold by the probe. A release
 * build has none of this: its list is Google's.
 */
object DebugHosts {
    @Volatile
    private var hosts: Map<String, Set<String>> = emptyMap()

    private fun file(context: Context) = File(context.filesDir, "debug-hosts.txt")

    fun load(context: Context) {
        hosts = runCatching {
            file(context).readLines().mapNotNull { line ->
                val (name, key) = line.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                name to setOf(KnownHosts.tidy(key) ?: return@mapNotNull null)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun listed(): Map<String, Set<String>> = hosts

    /** Adds a caller ("package sha256"), or with nothing forgets them all. Returns what is
     *  listed afterwards, in words. */
    fun set(context: Context, entry: String): String {
        val parts = entry.trim().split(' ', limit = 2)
        val key = parts.getOrNull(1)?.let(KnownHosts::tidy)
        hosts = if (parts.size == 2 && parts[0].isNotEmpty() && key != null) hosts + (parts[0] to setOf(key)) else emptyMap()
        runCatching { file(context).writeText(hosts.entries.joinToString("\n") { "${it.key} ${it.value.first()}" }) }
        return if (hosts.isEmpty()) "no test hosts" else "test hosts: " + hosts.keys.joinToString(", ")
    }
}
