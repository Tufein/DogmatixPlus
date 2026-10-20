package com.tufein.valkyr.util

/**
 * 3.8: the rules of the game page's new header (big cover, title, key chips, one big main button,
 * a row of round quick actions and a "More actions" card). Pure JVM for the tests; GameHeader.kt
 * and GamePage.kt only draw what these decide. What the main button does stays in
 * [GamePageModel.primary]; which state-dependent actions exist stays in [GamePageModel.actions].
 */
object GameHeaderModel {

    // ---- Key chips -------------------------------------------------------------------------------

    private val yearPattern = Regex("""(?<!\d)(19[5-9]\d|20\d\d)(?!\d)""")

    /**
     * The release year in a metadata date ("1998-11-21", "Nov 21, 1998", "1998"), or null when it
     * holds none. Only 1950..2099 counts, so a stray number (a size, a serial) is not taken for a year.
     */
    fun year(released: String?): String? = released?.let { yearPattern.find(it)?.value }

    /** The status badges under the key chips (console and size are key chips, not badges). */
    enum class Badge { REGION, SOURCE_TORRENT, SOURCE_ROMM, SOURCE_DIRECT, DOWNLOADING, ON_DEVICE, ON_ROMM, VERIFIED, FOUND, FAVOURITE }

    /**
     * The badges in the order they are drawn: the same facts the 8.0 info pills showed, minus the
     * console and the size (now key chips). A RomM file already on RomM shows only "on RomM";
     * "downloading" replaces "on device" while a download runs.
     */
    fun badges(
        hasRegion: Boolean,
        torrent: Boolean,
        fromRomm: Boolean,
        downloading: Boolean,
        owned: Boolean,
        onRomm: Boolean,
        verified: Boolean,
        found: Boolean,
        favourite: Boolean
    ): List<Badge> = buildList {
        if (hasRegion) add(Badge.REGION)
        when {
            torrent -> add(Badge.SOURCE_TORRENT)
            fromRomm -> if (!onRomm) add(Badge.SOURCE_ROMM)
            else -> add(Badge.SOURCE_DIRECT)
        }
        if (downloading) add(Badge.DOWNLOADING) else if (owned) add(Badge.ON_DEVICE)
        if (onRomm) add(Badge.ON_ROMM)
        if (verified) add(Badge.VERIFIED)
        if (found) add(Badge.FOUND)
        if (favourite) add(Badge.FAVOURITE)
    }

    // ---- Quick actions (round buttons under the main button) --------------------------------------

    enum class Quick { FAVOURITE, HOME_SCREEN, SHARE, MORE }

    /**
     * The round buttons: favourite (the star, also Select), a home-screen icon (only for a game on
     * the device and not downloading: the icon starts the file), share, and "more", which jumps to
     * the More actions card.
     */
    fun quick(owned: Boolean, downloading: Boolean, share: Boolean = true): List<Quick> = buildList {
        add(Quick.FAVOURITE)
        if (owned && !downloading) add(Quick.HOME_SCREEN)
        if (share) add(Quick.SHARE)
        add(Quick.MORE)
    }

    // ---- More actions card -----------------------------------------------------------------------

    enum class More { UP_NEXT, READINESS, HANDOFF, DOWNLOAD_AGAIN, EMULATOR, DOWNLOAD_BEST, UPDATE, DLC, DOWNLOAD_WHEN, COLLECTIONS, REMOVE }

    /**
     * Every action of the page that is neither the main button nor a round button, in the order
     * they are drawn (the 8.0 action row's order without favourite and share). Up next comes first
     * and is always there, so the "more" button always has a target to focus.
     */
    fun more(
        owned: Boolean,
        downloading: Boolean,
        readiness: Boolean,
        handoff: Boolean,
        betterVersion: Boolean,
        updateAvailable: Boolean,
        missingDlc: Int
    ): List<More> = buildList {
        add(More.UP_NEXT)
        if (readiness) add(More.READINESS)
        if (handoff) add(More.HANDOFF)
        if (owned && !downloading) {
            add(More.DOWNLOAD_AGAIN)
            add(More.EMULATOR)
        }
        GamePageModel.actions(owned, downloading, betterVersion, updateAvailable, missingDlc).forEach { action ->
            when (action) {
                GamePageModel.Action.DOWNLOAD_BEST -> add(More.DOWNLOAD_BEST)
                GamePageModel.Action.UPDATE -> add(More.UPDATE)
                GamePageModel.Action.DLC -> add(More.DLC)
                GamePageModel.Action.DOWNLOAD_WHEN -> add(More.DOWNLOAD_WHEN)
                GamePageModel.Action.COLLECTIONS -> add(More.COLLECTIONS)
                GamePageModel.Action.REMOVE -> add(More.REMOVE)
                // Round buttons now.
                GamePageModel.Action.FAVOURITE, GamePageModel.Action.SHARE -> Unit
            }
        }
    }

    // ---- Sizes -----------------------------------------------------------------------------------

    /**
     * Width (dp) of the cover in the portrait header: about 40% of the screen, kept between
     * [min] and [max] so a narrow phone still shows the title beside it and a tablet does not get
     * a poster.
     */
    fun portraitCoverWidth(screenWidthDp: Int, min: Int = 120, max: Int = 240): Int =
        (screenWidthDp * 0.4f).toInt().coerceIn(min, max)
}
