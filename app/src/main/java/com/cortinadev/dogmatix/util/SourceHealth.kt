package com.cortinadev.dogmatix.util

/**
 * How a console's sources are doing, for the 5.0 Sources cards: one state from the last scan of
 * each enabled source. Pure JVM for the tests.
 */
object SourceHealth {

    enum class State {
        /** The console has no enabled source. */
        NO_SOURCES,
        /** None of its sources has been scanned yet. */
        NOT_SCANNED,
        /** Every scanned source answered (some may not be scanned yet). */
        OK,
        /** Some sources failed, others answered. */
        PARTIAL,
        /** Every scanned source failed. */
        FAILED
    }

    data class Health(val state: State, val failed: Int = 0, val scanned: Int = 0)

    /**
     * @param outcomes one entry per enabled source: true = its last scan worked, false = it failed,
     *   null = never scanned.
     */
    fun of(outcomes: List<Boolean?>): Health {
        if (outcomes.isEmpty()) return Health(State.NO_SOURCES)
        val scanned = outcomes.count { it != null }
        val failed = outcomes.count { it == false }
        val state = when {
            scanned == 0 -> State.NOT_SCANNED
            failed == 0 -> State.OK
            failed == scanned -> State.FAILED
            else -> State.PARTIAL
        }
        return Health(state, failed, scanned)
    }
}

/** What kind of address a source is, for its icon and label in Sources. */
enum class SourceKind {
    ROMM, TORRENT, WEB, OTHER;

    companion object {
        /**
         * RomM platforms (`romm://`), torrents (magnets, `.torrent` links, picked or stored torrent
         * files), web directories (http/https); anything else is [OTHER].
         */
        fun of(url: String): SourceKind {
            val u = url.trim()
            val path = u.substringBefore('?').substringBefore('#')
            return when {
                RommSource.isSource(u) -> ROMM
                u.startsWith("magnet:", ignoreCase = true) -> TORRENT
                path.endsWith(".torrent", ignoreCase = true) -> TORRENT
                u.startsWith("/") || u.startsWith("content://", ignoreCase = true) -> TORRENT
                u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> WEB
                else -> OTHER
            }
        }
    }
}
