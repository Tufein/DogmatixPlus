package com.cortinadev.dogmatix.util

object FileSizeUtils {

    /** Compiled once: every row of a directory listing goes through here. */
    private val sizePattern = """(\d+(?:\.\d+)?)\s*([KMGTPE]?i?B)""".toRegex(RegexOption.IGNORE_CASE)
    
    /** Apache index sizes: a number and one unit letter, in powers of 1024 (`66M`, `1.5K`). */
    private val apachePattern = """(\d+(?:\.\d+)?)([KMGTPE])""".toRegex(RegexOption.IGNORE_CASE)

    private fun apacheSize(text: String): Long {
        val (numberStr, unit) = apachePattern.matchEntire(text)?.destructured ?: return 0L
        val number = numberStr.toDoubleOrNull() ?: return 0L
        return (number * Math.pow(1024.0, "KMGTPE".indexOf(unit.uppercase()) + 1.0)).toLong()
    }

    fun parseFileSize(sizeString: String): Long {
        if (sizeString.isBlank() || sizeString == "0") return 0L
        
        val cleanString = sizeString.trim().replace(",", "")
        val match = sizePattern.find(cleanString)
        
        if (match == null) return apacheSize(cleanString)
        
        val (numberStr, unitStr) = match.destructured
        val number = numberStr.toDoubleOrNull() ?: return 0L
        
        return when (unitStr.uppercase()) {
            "B" -> number.toLong()
            "KB" -> (number * Constants.KILOBYTE).toLong()
            "KIB" -> (number * Constants.KIBIBYTE).toLong()
            "MB" -> (number * Constants.MEGABYTE).toLong()
            "MIB" -> (number * Constants.MEBIBYTE).toLong()
            "GB" -> (number * Constants.GIGABYTE).toLong()
            "GIB" -> (number * Constants.GIBIBYTE).toLong()
            "TB" -> (number * Constants.TERABYTE).toLong()
            "TIB" -> (number * Constants.TEBIBYTE).toLong()
            "PB" -> (number * Constants.TERABYTE * Constants.KILOBYTE).toLong()
            "PIB" -> (number * Constants.TEBIBYTE * Constants.KIBIBYTE).toLong()
            "EB" -> (number * Constants.TERABYTE * Constants.MEGABYTE).toLong()
            "EIB" -> (number * Constants.TEBIBYTE * Constants.MEBIBYTE).toLong()
            else -> 0L
        }
    }
    
    fun bytesToMB(bytes: Long): Float {
        return bytes / Constants.MEBIBYTE.toFloat()
    }
}
