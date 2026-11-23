package org.medianexus.music.playback

/**
 * What the car's searches found, by what was asked, from the moment a search is made until
 * the car asks for its results. It holds the last few only, and belongs to one account's
 * turn at the player: a new account starts with none.
 */
class SearchResults(private val most: Int = MOST) {
    private val found = object : LinkedHashMap<String, List<CarEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CarEntry>>?) = size > most
    }

    @Synchronized
    fun put(query: String, entries: List<CarEntry>) {
        found[query] = entries
    }

    @Synchronized
    fun get(query: String): List<CarEntry>? = found[query]

    @Synchronized
    fun forget(query: String) {
        found.remove(query)
    }

    @Synchronized
    fun size(): Int = found.size

    private companion object {
        const val MOST = 16
    }
}
