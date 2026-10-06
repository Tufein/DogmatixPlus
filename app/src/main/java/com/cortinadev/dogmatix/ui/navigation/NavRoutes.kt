package com.cortinadev.dogmatix.ui.navigation

import com.cortinadev.dogmatix.R

sealed class NavRoutes(val route: String, val labelRes: Int, val icon: Int) {
    object Home : NavRoutes("home", R.string.nav_library, R.drawable.ic_controller)
    object Downloads : NavRoutes("downloads", R.string.nav_downloads, R.drawable.ic_download)
    object Sources : NavRoutes("sources", R.string.nav_sources, R.drawable.ic_globe)
    object Settings : NavRoutes("settings", R.string.nav_settings, R.drawable.ic_settings)
    object Contact : NavRoutes("contact", R.string.nav_contact, R.drawable.ic_heart)
    object Romm : NavRoutes("romm", R.string.nav_romm, R.drawable.ic_server)
    object SaveSync : NavRoutes("save_sync", R.string.nav_save_sync, R.drawable.ic_cloud_sync)
    object Overview : NavRoutes("overview", R.string.nav_overview, R.drawable.ic_dashboard)
    object Duplicates : NavRoutes("duplicates", R.string.nav_duplicates, R.drawable.ic_duplicates)
    object Tools : NavRoutes("tools", R.string.settings_tools, R.drawable.ic_build)
    object Sets : NavRoutes("sets", R.string.nav_sets, R.drawable.ic_stacks)
    object Storage : NavRoutes("storage", R.string.nav_storage, R.drawable.ic_storage)
    object Wishlist : NavRoutes("wishlist", R.string.nav_wishlist, R.drawable.ic_wishlist)
    object Files : NavRoutes("files", R.string.nav_files, R.drawable.ic_folder_open)
    object Collections : NavRoutes("collections", R.string.nav_collections, R.drawable.ic_collections)
    object Switch : NavRoutes("switch", R.string.nav_switch, R.drawable.ic_joystick)
    object Dat : NavRoutes("dat", R.string.nav_dat, R.drawable.ic_verified)
    object ImportList : NavRoutes("import_list", R.string.nav_import_list, R.drawable.ic_playlist_add)
    object Bios : NavRoutes("bios", R.string.nav_bios, R.drawable.ic_memory)
    object Stats : NavRoutes("stats", R.string.nav_stats, R.drawable.ic_bar_chart)
    object Profiles : NavRoutes("profiles", R.string.settings_profiles, R.drawable.ic_account)
    object Frontends : NavRoutes("frontends", R.string.nav_frontends, R.drawable.ic_frontends)
    object RetroAchievements : NavRoutes("retroachievements", R.string.nav_ra, R.drawable.ic_trophy)
    /** 5.0: everything that lives online in one place (RomM, saves, cloud backup, devices, RetroAchievements). */
    object Cloud : NavRoutes("cloud", R.string.nav_cloud, R.drawable.ic_cloud)
    /** 5.0: encrypted backup and device sync on the user's own WebDAV server (Nextcloud, ownCloud, Synology…). */
    object CloudBackup : NavRoutes("cloud_backup", R.string.dav_title, R.drawable.ic_cloud_upload)

    /** 6.0: how complete each console's collection is. */
    object CollectionGoals : NavRoutes("collection_goals", R.string.coll6_goals_title, R.drawable.ic_collection_goals)
    /** 6.0: what was downloaded and played, by day. */
    object History : NavRoutes("play_history", R.string.coll6_history_title, R.drawable.ic_history)
    /** 6.0: every health check in one report, with a fix per problem. */
    object Health : NavRoutes("health", R.string.health6_title, R.drawable.ic_health)

    /** 7.0: the year in games, with a card to share. */
    object Recap : NavRoutes("recap", R.string.quick7_title, R.drawable.ic_sparkle)
    /** 7.0: the most-loved games per console (RetroAchievements) against the library. */
    object BestGames : NavRoutes("bestgames", R.string.top7_title, R.drawable.ic_military_tech)
    /** 7.0: write descriptions, genres and years into ES-DE and Pegasus. */
    object FrontendMetadata : NavRoutes("frontend_metadata", R.string.meta7_title, R.drawable.ic_description)
    /** 7.0: a newer revision or a clean dump of a game you already have. */
    object BetterVersions : NavRoutes("better_versions", R.string.upg7_better_title, R.drawable.ic_better_version)

    companion object {
        /** The four sections shown as tabs; Contact, RomM and the library tools are reached from Settings. */
        val tabs by lazy { listOf(Home, Downloads, Sources, Settings) }
        val allRoutes by lazy { tabs + Contact + Romm + SaveSync + Overview + Duplicates + Tools + Sets + Storage + Wishlist + Files + Collections + Switch + Dat + ImportList + Bios + Stats + Profiles + RetroAchievements + Frontends + Cloud + CloudBackup + CollectionGoals + History + Health + Recap + BestGames + FrontendMetadata + BetterVersions }
    }
}
