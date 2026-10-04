package com.cortinadev.dogmatix.ui.navigation

import com.cortinadev.dogmatix.R

sealed class NavRoutes(val route: String, val labelRes: Int, val icon: Int) {
    object Home : NavRoutes("home", R.string.nav_library, R.drawable.ic_search)
    object Downloads : NavRoutes("downloads", R.string.nav_downloads, R.drawable.ic_arrow_down)
    object Sources : NavRoutes("sources", R.string.nav_sources, R.drawable.ic_folder)
    object Settings : NavRoutes("settings", R.string.nav_settings, R.drawable.ic_settings)
    object Contact : NavRoutes("contact", R.string.nav_contact, R.drawable.ic_edit)
    object Romm : NavRoutes("romm", R.string.nav_romm, R.drawable.ic_arrow_up)
    object SaveSync : NavRoutes("save_sync", R.string.nav_save_sync, R.drawable.ic_arrow_up)
    object Overview : NavRoutes("overview", R.string.nav_overview, R.drawable.ic_folder)
    object Duplicates : NavRoutes("duplicates", R.string.nav_duplicates, R.drawable.ic_folder)
    object Tools : NavRoutes("tools", R.string.settings_tools, R.drawable.ic_folder)
    object Sets : NavRoutes("sets", R.string.nav_sets, R.drawable.ic_folder)
    object Storage : NavRoutes("storage", R.string.nav_storage, R.drawable.ic_folder)
    object Wishlist : NavRoutes("wishlist", R.string.nav_wishlist, R.drawable.ic_star)
    object Files : NavRoutes("files", R.string.nav_files, R.drawable.ic_folder)
    object Collections : NavRoutes("collections", R.string.nav_collections, R.drawable.ic_star)
    object Switch : NavRoutes("switch", R.string.nav_switch, R.drawable.ic_gamepad)
    object Dat : NavRoutes("dat", R.string.nav_dat, R.drawable.ic_check)
    object Bios : NavRoutes("bios", R.string.nav_bios, R.drawable.ic_check)
    object Stats : NavRoutes("stats", R.string.nav_stats, R.drawable.ic_sort)
    object Profiles : NavRoutes("profiles", R.string.settings_profiles, R.drawable.ic_settings)
    object Frontends : NavRoutes("frontends", R.string.nav_frontends, R.drawable.ic_check)
    object RetroAchievements : NavRoutes("retroachievements", R.string.nav_ra, R.drawable.ic_star)

    companion object {
        /** The four sections shown as tabs; Contact, RomM and the library tools are reached from Settings. */
        val tabs by lazy { listOf(Home, Downloads, Sources, Settings) }
        val allRoutes by lazy { tabs + Contact + Romm + SaveSync + Overview + Duplicates + Tools + Sets + Storage + Wishlist + Files + Collections + Switch + Dat + Bios + Stats + Profiles + RetroAchievements + Frontends }
    }
}
