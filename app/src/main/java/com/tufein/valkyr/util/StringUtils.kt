package com.tufein.valkyr.util

object StringUtils {
    fun capitalizeWords(text: String): String {
        return text.split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { 
                    if (it.isLowerCase()) it.titlecase() else it.toString() 
                }
            }
    }
}
