package com.cortinadev.dogmatix.util

/**
 * Which finished downloads still have to go to the RomM server: the ones the server does not list,
 * that are still on the device, for a console that is mapped to a RomM platform and did not come
 * from the server itself. Pure JVM for the tests.
 */
object RommUploadPlan {
    data class Candidate(val fileName: String, val consoleId: String, val downloadUrl: String)

    fun missing(
        candidates: List<Candidate>,
        serverKeys: Set<String>,
        mappedConsoles: Set<String>,
        serverBase: String,
        onDevice: (Candidate) -> Boolean
    ): List<String> = candidates
        .filter { it.consoleId in mappedConsoles }
        .filterNot { RommMarks.isOnServer(serverKeys, it.consoleId, it.fileName, it.downloadUrl, serverBase) }
        .filter(onDevice)
        .map { it.fileName }
        .distinct()
}
