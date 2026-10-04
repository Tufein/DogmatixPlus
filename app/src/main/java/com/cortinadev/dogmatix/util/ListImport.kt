package com.cortinadev.dogmatix.util

/**
 * Reading a list of games the user wants (a text file or the clipboard): one game per line.
 * Numbering, bullets, quotes, comment lines and extra columns are left out.
 */
object ListImport {
    const val MAX_TITLES = 2000

    /** "1. ", "2) ", "3 - " or a bullet; "3-D Worldrunner" and "1942" stay as they are. */
    private val bullet = Regex("""^(?:\d{1,4}(?:[.)]|\s+[-–])\s+|[-*•·>]\s+)""")
    private val quotes = Regex("""^["'“‘]+|["'”’]+$""")

    fun titles(text: String): List<String> =
        text.removePrefix("﻿").lineSequence()
            .map { line -> line.substringBefore('\t').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .map { it.replace(bullet, "").replace(quotes, "").trim() }
            .filter { GameTitleCleaner.words(it).isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_TITLES)
            .toList()

    /** The word that narrows the library search most (the longest), or null when there is none. */
    fun searchWord(title: String): String? = GameTitleCleaner.words(title).maxByOrNull { it.length }
}
