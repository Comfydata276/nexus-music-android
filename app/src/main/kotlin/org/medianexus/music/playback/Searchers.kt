package org.medianexus.music.playback

/**
 * Which car browser made which search, lately: told, each of them, when its search is to be
 * made again ("Tap to try again"). Kept by browser as well as by the words, so a second car
 * (or Android Auto and the system's own media controls) searching the same words doesn't
 * take the first one's place; and the last few only, so it never grows. Plain Kotlin, so it
 * can be tested on its own; `B` is Media3's `ControllerInfo` in the service.
 */
class Searchers<B>(private val most: Int = MOST) {
    private val made = object : LinkedHashMap<Pair<B, String>, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<B, String>, Unit>?) = size > most
    }

    /** `browser` searched for `query`. */
    @Synchronized
    fun made(browser: B, query: String) {
        made[browser to query] = Unit
    }

    /** Everyone who searched for `query` lately. */
    @Synchronized
    fun of(query: String): List<B> = made.keys.filter { it.second == query }.map { it.first }

    @Synchronized
    fun size(): Int = made.size

    private companion object {
        /** As many as the car's searches kept (`SearchResults`), and some. */
        const val MOST = 32
    }
}
