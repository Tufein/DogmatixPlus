package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.util.BiosCatalog
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.HashAlgo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a BIOS check: per system, which files are there and good. */
data class BiosReport(val folderSet: Boolean, val results: List<BiosCatalog.SystemResult>)

/**
 * Checks the emulator's BIOS folder (picked once in the BIOS screen) against [BiosCatalog]: for
 * the consoles in Sources, or for every known system. Files are found by name without case,
 * one level of sub-folders deep (`dc/dc_boot.bin`), and recognised by MD5.
 */
@Singleton
class BiosService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val consoleDao: ConsoleDao
) {
    suspend fun check(allSystems: Boolean): BiosReport = withContext(Dispatchers.IO) {
        val consoles = consoleDao.getAllConsoles().first().map { it.id }
        val systems = if (allSystems) BiosCatalog.systems else BiosCatalog.systemsFor(consoles)
        val root = appSettings.biosDir.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
            ?: return@withContext BiosReport(false, systems.map { BiosCatalog.check(it, emptyMap()) { null } })
        val files = HashMap<String, DiskEntry>()
        collect(root, "", depth = 1, into = files)
        val present = files.mapValues { it.value.name }
        val hashes = HashMap<String, String?>()
        fun md5(path: String): String? = hashes.getOrPut(path) {
            val entry = files[path] ?: return@getOrPut null
            if (entry.size > 64L * 1024 * 1024) return@getOrPut null
            runCatching { context.contentResolver.openInputStream(entry.uri)?.use { Checksums.hexOf(it, HashAlgo.MD5) } }.getOrNull()
        }
        BiosReport(true, systems.map { BiosCatalog.check(it, present, ::md5) })
    }

    private fun collect(dir: DiskDir, prefix: String, depth: Int, into: MutableMap<String, DiskEntry>) {
        for (e in DiskScanner.list(context, dir)) {
            val path = prefix + e.name.lowercase()
            if (e.isDirectory) { if (depth > 0) collect(DiskScanner.dirOf(dir, e), "$path/", depth - 1, into) }
            else into[path] = e
        }
    }
}
