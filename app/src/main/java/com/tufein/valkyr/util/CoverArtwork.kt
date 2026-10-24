package com.tufein.valkyr.util

import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.CoverSources.Source

/**
 * The source order of the second picture field, "screenshots and title screens" (5.6.0), kept apart from the order of
 * the covers ([CoverSources]). The covers of the library come from [CoverSources.Chain]; these pictures are the extra
 * image types of Tools → Frontend covers (`titlescreens/`, `screenshots/`), which until 5.5 were taken from
 * libretro-thumbnails only.
 *
 * - Three sources can supply them: libretro-thumbnails (title screens and snaps, as before), ScreenScraper (its
 *   `sstitle` and `ss` pictures, with the user's own login) and the RomM server (the game's screenshots; RomM keeps no
 *   title screens). The other cover sources are box art services and are not offered here.
 * - Unlike the covers, RomM is not pinned first: it can be moved like the others.
 * - Until the user changes a switch here, behaviour is the one of 5.5: only libretro-thumbnails is asked, and when
 *   that is switched off for the covers it is off here too ([disabledOf]). ScreenScraper and RomM start switched off.
 * - Regions: ScreenScraper picks the picture of the first preferred region that has one. That list is the one of the
 *   game's console in the version preferences (the cover chain uses the same), not a setting of this screen.
 */
object CoverArtwork {

    /** The sources that can supply these pictures, in the order they start in. */
    val SOURCES = listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.ROMM)

    private val OFF_UNTIL_CHOSEN = setOf(Source.SCREENSCRAPER, Source.ROMM)

    const val ORDER_KEY = "covers55_art_order"
    const val DISABLED_KEY = "covers55_art_disabled"

    /** Type tags of the settings (see [SettingSchema]). */
    val SETTING_TYPES: Map<String, String> = mapOf(ORDER_KEY to "s", DISABLED_KEY to "ss")

    /** The saved order: unknown names and sources that cannot supply these pictures dropped, a missing one put back at its default place. */
    fun orderOf(text: String?): List<Source> {
        val out = text.orEmpty().split(',').mapNotNull { Source.of(it) }.filter { it in SOURCES }.distinct().toMutableList()
        SOURCES.forEachIndexed { index, source -> if (source !in out) out.add(index.coerceAtMost(out.size), source) }
        return out
    }

    fun encodeOrder(order: List<Source>): String = order.filter { it in SOURCES }.distinct().joinToString(",") { it.key }

    /**
     * The switched-off sources. Before the user ever touched a switch here ([keys] null) the 5.5 behaviour stands: ScreenScraper
     * and RomM are off, libretro-thumbnails follows its switch in the cover chain ([coverDisabled]).
     */
    fun disabledOf(keys: Set<String>?, coverDisabled: Set<Source>): Set<Source> =
        if (keys == null) OFF_UNTIL_CHOSEN + (coverDisabled intersect setOf(Source.LIBRETRO))
        else keys.mapNotNull { Source.of(it) }.filter { it in SOURCES }.toSet()

    data class Chain(val order: List<Source> = SOURCES, val disabled: Set<Source> = OFF_UNTIL_CHOSEN) {
        /** The sources to ask, in order. */
        val active: List<Source> get() = order.filter { it !in disabled }
        fun enabled(source: Source): Boolean = source !in disabled
    }

    fun move(order: List<Source>, source: Source, delta: Int): List<Source> = CoverSources.move(order, source, delta)

    /** Whether [source] has this kind of picture at all (the RomM server keeps screenshots, no title screens). */
    fun supports(source: Source, art: LibretroThumbnails.Art): Boolean = when (source) {
        Source.ROMM -> art == LibretroThumbnails.Art.SNAP
        Source.LIBRETRO, Source.SCREENSCRAPER -> art == LibretroThumbnails.Art.TITLE || art == LibretroThumbnails.Art.SNAP
        else -> false
    }

    /**
     * What the sources asked in turn amount to: the first picture found; else, when any source could not answer (offline,
     * rate limit), not a miss; else a miss.
     */
    fun verdict(answers: List<Answer>): Answer =
        answers.firstOrNull { it is Answer.Found } ?: if (answers.any { it == Answer.Unavailable }) Answer.Unavailable else Answer.Miss
}
