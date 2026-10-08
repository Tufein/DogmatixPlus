package com.cortinadev.dogmatix.util

object GameReadiness {
    fun referencesPresent(name: String, text: String, siblings: Collection<String>): Boolean {
        if (!SheetParser.isSheet(name)) return true
        val refs = when (name.substringAfterLast('.').lowercase()) {
            "cue" -> SheetParser.cueFiles(text)
            "gdi" -> SheetParser.gdiFiles(text)
            else -> SheetParser.m3uFiles(text)
        }
        val names = siblings.map { it.lowercase() }.toSet()
        return refs.isNotEmpty() && refs.all { GameArtifacts.safeLaunchReference(it) && it.lowercase() in names }
    }
}
