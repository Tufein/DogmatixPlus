package com.cortinadev.dogmatix.util

/** A scanned file for the library tool tests. */
fun diskFile(
    name: String,
    folder: String = "/ROMs/psx",
    size: Long = 1_000,
    consoleId: String? = "psx",
    scope: String = consoleId ?: "folder:x",
    dirId: String = folder,
    level: Int = 0
) = DiskFile(
    scope = scope, consoleId = consoleId, folder = folder, name = name, size = size,
    uri = "content://t/$dirId/$name", dirId = dirId, fileId = "$dirId/$name", level = level, dirUri = "content://t/dir/$dirId"
)
