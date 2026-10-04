package com.cortinadev.dogmatix.util

/**
 * Where frontends other than ES-DE look for a game's cover (see [EsdeArtwork] for ES-DE).
 * Pure JVM for the tests.
 *
 * - **Pegasus**: `media/<game file name without extension>/boxFront.<ext>` next to the games.
 * - **RetroArch** playlists: `<thumbnails folder>/<system>/Named_Boxarts/<name>.png`, the
 *   system as libretro names it ("Nintendo - Game Boy Advance") and the name with libretro's
 *   character rule applied. RetroArch reads PNG, so its covers come from libretro-thumbnails.
 */
object FrontendArtwork {

    /** `Game (USA).gba` → `media/Game (USA)/boxFront.jpg` (relative to the game's folder). */
    fun pegasusCoverPath(romFileName: String, imageExtension: String): String =
        "media/${LibraryKeys.baseName(romFileName)}/boxFront.$imageExtension"

    /** RetroArch's playlist / thumbnail folder name of a libretro-thumbnails system. */
    fun retroArchSystem(system: LibretroThumbnails.System): String = system.repo.replace('_', ' ')

    /** `Nintendo - Game Boy Advance/Named_Boxarts/Game (USA).png` (relative to RetroArch's thumbnails folder). */
    fun retroArchCoverPath(system: LibretroThumbnails.System, romFileName: String): String =
        "${retroArchSystem(system)}/Named_Boxarts/${LibretroThumbnails.thumbnailName(LibraryKeys.baseName(romFileName))}.png"
}
