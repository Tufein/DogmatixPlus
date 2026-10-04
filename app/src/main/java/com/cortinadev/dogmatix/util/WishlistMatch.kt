package com.cortinadev.dogmatix.util

/**
 * Whether a wanted game is still wanted: it may already be on the device (in the download
 * folders, by the on-disk index) or on the RomM server (by its game list), not only in a
 * source. A name counts when every word of the wish is in it, like the automatic download.
 * Pure JVM for the tests.
 */
object WishlistMatch {
    enum class State { WANTED, IN_SOURCES, IN_ROMM, ON_DEVICE }

    /** [rommKeys]: `consoleId|name` as [RommMarks] makes them. */
    fun inRomm(title: String, consoleId: String?, rommKeys: Set<String>): Boolean =
        rommKeys.any { key ->
            val console = key.substringBefore('|')
            (consoleId == null || console == consoleId) && GameTitleCleaner.containsAllWords(title, key.substringAfter('|'))
        }

    /**
     * [ownedKeys]: `scope|name` as [LibraryKeys] makes them; [scopes] are the scopes that count
     * for the wish's console ([LibraryKeys.scopesFor]), null for any console.
     */
    fun onDevice(title: String, scopes: Set<String>?, ownedKeys: Set<String>): Boolean =
        ownedKeys.any { key ->
            (scopes == null || key.substringBefore('|') in scopes) && GameTitleCleaner.containsAllWords(title, key.substringAfter('|'))
        }

    /** What the wishlist shows: having the game beats finding it in a source. */
    fun state(onDevice: Boolean, inRomm: Boolean, sourceMatches: Int): State = when {
        onDevice -> State.ON_DEVICE
        inRomm -> State.IN_ROMM
        sourceMatches > 0 -> State.IN_SOURCES
        else -> State.WANTED
    }

    /** Still worth a notification or an automatic download. */
    fun stillWanted(state: State): Boolean = state == State.WANTED || state == State.IN_SOURCES
}
