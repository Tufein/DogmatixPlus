package com.tufein.valkyr.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tufein.valkyr.R
import com.tufein.valkyr.util.SettingKeys as K

/**
 * The cards of the Settings screen, in the order they are shown, with their heading and icon.
 * [title] null is the way into Tools: first, without a heading, on the accent card.
 */
enum class SettingsGroup(@StringRes val title: Int?, @DrawableRes val icon: Int) {
    TOOLS(null, R.drawable.ic_build),
    LOOK(R.string.settings43_group_look, R.drawable.ic_palette),
    CONTROLS(R.string.settings43_group_controls, R.drawable.ic_gamepad),
    STORAGE(R.string.settings43_group_storage, R.drawable.ic_folder_open),
    DOWNLOADS(R.string.settings_section_downloads, R.drawable.ic_download),
    SCHEDULE(R.string.settings_section_schedule, R.drawable.ic_schedule),
    AFTER(R.string.settings_section_after, R.drawable.ic_archive),
    TORRENTS(R.string.settings_section_torrents, R.drawable.ic_p2p),
    LIBRARY(R.string.settings_section_library, R.drawable.ic_library),
    FRONTENDS(R.string.settings43_group_frontends, R.drawable.ic_frontends),
    CLOUD(R.string.settings43_group_cloud, R.drawable.ic_cloud),
    BACKUP(R.string.settings_section_backup, R.drawable.ic_backup),
    APP(R.string.settings_section_app, R.drawable.ic_info)
}

/**
 * The switches and choices that show or hide other rows. Every dependent row is hidden while its
 * master is off (one rule for the whole screen) and sits directly under it.
 */
data class SettingsMasters(
    val speedLimitOn: Boolean = false,
    val speedLimitDayOnly: Boolean = false,
    val lowBatteryOn: Boolean = false,
    val nightOnly: Boolean = false,
    val autoScanOn: Boolean = false,
    val autoScanNightOnly: Boolean = false,
    val debridOn: Boolean = false,
    val autoBackupOn: Boolean = false
)

/**
 * Where every Settings row lives (4.3): its card, its place in the card (most used first, the
 * advanced last; a master switch is directly followed by its own rows) and when it shows. The
 * screen and "search everything" both read it, so a row found by search names the card it is in.
 */
object SettingsLayout {

    /** The rows of each card, in order. */
    val groups: Map<SettingsGroup, List<String>> = linkedMapOf(
        SettingsGroup.TOOLS to listOf(K.TOOLS, K.SEARCH_ALL),
        SettingsGroup.LOOK to listOf(
            K.THEME, K.ACCENT, K.LANGUAGE, K.TEXT_SIZE, K.COMPACT, K.LIST_COVERS, K.ANIMATIONS, K.GLOW
        ),
        SettingsGroup.CONTROLS to listOf(K.GAMEPAD_LAYOUT, K.SWAP_BUTTONS, K.BOLD_FOCUS, K.TV_MODE, K.SECOND_SCREEN),
        SettingsGroup.STORAGE to listOf(K.DOWNLOAD_DIR, K.SEPARATE_CONSOLE, K.MIN_FREE, K.STORAGE),
        SettingsGroup.DOWNLOADS to listOf(
            K.CONCURRENT, K.SPEED_LIMIT, K.SPEED_DAY_ONLY, K.PER_SERVER, K.PICK_BEST,
            K.AUTO_RETRY, K.RESUME, K.REQUEUE, K.QUEUE_SUMMARY
        ),
        SettingsGroup.SCHEDULE to listOf(
            K.WIFI, K.CHARGING, K.LOW_BATTERY, K.BATTERY_LEVEL, K.HEAT, K.NIGHT, K.NIGHT_START, K.NIGHT_END,
            K.DOWNLOAD_PRESETS
        ),
        SettingsGroup.AFTER to listOf(
            K.AUTO_UNZIP, K.AUTO_M3U, K.META_AUTO, K.ESDE_ARTWORK, K.PEGASUS_ARTWORK, K.RETROARCH_ARTWORK
        ),
        SettingsGroup.TORRENTS to listOf(K.DEBRID, K.DEBRID_KEY, K.METADATA_TIMEOUT),
        SettingsGroup.LIBRARY to listOf(
            K.AUTOSCAN, K.AUTOSCAN_EVERY, K.AUTOSCAN_WIFI, K.AUTOSCAN_CHARGING, K.AUTOSCAN_NIGHT,
            K.PROFILES, K.VERSION_PREF, K.FAVORITE_LANGUAGES, K.WISHLIST_AUTO, K.COVERS_RETRY, K.MAX_RESULTS, K.DIGEST
        ),
        SettingsGroup.FRONTENDS to listOf(
            K.ESDE, K.IISU, K.DAIJISHO, K.COCOON, K.EMULATORS, K.FRONTEND_SHORTCUTS, K.RECENT_MENU
        ),
        SettingsGroup.CLOUD to listOf(K.CLOUD, K.ROMM, K.SAVE_SYNC, K.SHELF),
        SettingsGroup.BACKUP to listOf(K.BACKUP_EXPORT, K.BACKUP_IMPORT, K.AUTO_BACKUP, K.AUTO_BACKUP_FOLDER, K.CLOUD_BACKUP),
        SettingsGroup.APP to listOf(K.UPDATE_CHECK, K.PRERELEASES, K.DIAGNOSTICS, K.REPORT, K.ABOUT)
    )

    /** A dependent row and the master row it hangs under. */
    val parents: Map<String, String> = mapOf(
        K.SPEED_DAY_ONLY to K.SPEED_LIMIT,
        K.BATTERY_LEVEL to K.LOW_BATTERY,
        K.NIGHT_START to K.NIGHT,
        K.NIGHT_END to K.NIGHT,
        K.DEBRID_KEY to K.DEBRID,
        K.AUTOSCAN_EVERY to K.AUTOSCAN,
        K.AUTOSCAN_WIFI to K.AUTOSCAN,
        K.AUTOSCAN_CHARGING to K.AUTOSCAN,
        K.AUTOSCAN_NIGHT to K.AUTOSCAN,
        K.AUTO_BACKUP_FOLDER to K.AUTO_BACKUP
    )

    private val groupByKey: Map<String, SettingsGroup> =
        groups.flatMap { (group, keys) -> keys.map { it to group } }.toMap()

    private val placeByKey: Map<String, Int> =
        groups.values.flatMap { it }.withIndex().associate { (i, key) -> key to i }

    /** The card of [key]; null for a row this layout does not know (it keeps the card it asks for). */
    fun groupOf(key: String?): SettingsGroup? = key?.let { groupByKey[it] }

    /** The place of [key] over the whole screen; unknown rows go after the known ones of their card. */
    fun placeOf(key: String?): Int = key?.let { placeByKey[it] } ?: Int.MAX_VALUE

    /** The row a search result for [key] should jump to: the master of a dependent row (that one always shows). */
    fun jumpTarget(key: String): String = parents[key] ?: key

    /**
     * The night hours (start / end) are used by three switches: downloads only at night, no speed
     * limit at night and scanning only at night. They show while any of them is in effect, so a
     * user can always see and change the hours a switch of theirs depends on.
     */
    fun nightHoursInUse(m: SettingsMasters): Boolean =
        m.nightOnly || (m.speedLimitOn && m.speedLimitDayOnly) || (m.autoScanOn && m.autoScanNightOnly)

    /** Whether the row [key] shows with the masters in [m]; rows without a master always show. */
    fun isShown(key: String?, m: SettingsMasters): Boolean = when (key) {
        K.SPEED_DAY_ONLY -> m.speedLimitOn
        K.BATTERY_LEVEL -> m.lowBatteryOn
        K.NIGHT_START, K.NIGHT_END -> nightHoursInUse(m)
        K.DEBRID_KEY -> m.debridOn
        K.AUTOSCAN_EVERY, K.AUTOSCAN_WIFI, K.AUTOSCAN_CHARGING, K.AUTOSCAN_NIGHT -> m.autoScanOn
        K.AUTO_BACKUP_FOLDER -> m.autoBackupOn
        else -> true
    }
}
