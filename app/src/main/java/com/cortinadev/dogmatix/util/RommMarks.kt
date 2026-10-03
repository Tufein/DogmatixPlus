package com.cortinadev.dogmatix.util

/**
 * Which games the RomM server already has, as keys comparable with library rows: console id plus
 * the lower-cased file name without extension (so `Game.zip` on the server matches `Game.gba` in
 * the library, like the on-device owned mark does).
 */
object RommMarks {

    fun key(consoleId: String, fileName: String): String =
        consoleId + "|" + LibraryKeys.baseName(FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase())

    fun keys(consoleId: String, fileNames: Collection<String>): Set<String> =
        fileNames.mapTo(HashSet(fileNames.size)) { key(consoleId, it) }

    /** True when the server lists the game, or the row itself comes from the server. */
    fun isOnServer(keys: Set<String>, consoleId: String, fileName: String, downloadUrl: String = "", serverBase: String = ""): Boolean =
        (serverBase.isNotEmpty() && RommSource.isDownloadFrom(serverBase, downloadUrl)) || key(consoleId, fileName) in keys

    /** Games on the device that the server does not have yet (candidates for an upload). */
    fun missingOnServer(keys: Set<String>, consoleId: String, deviceFileNames: Collection<String>): List<String> =
        deviceFileNames.filter { key(consoleId, it) !in keys }
}
