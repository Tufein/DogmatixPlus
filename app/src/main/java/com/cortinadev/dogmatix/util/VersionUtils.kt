package com.cortinadev.dogmatix.util

object VersionUtils {
    
    /**
     * Compares two version strings and returns:
     * -1 if version1 < version2
     * 0 if version1 == version2  
     * 1 if version1 > version2
     */
    fun compareVersions(version1: String, version2: String): Int {
        val v1 = parseVersion(version1)
        val v2 = parseVersion(version2)
        
        val maxLength = maxOf(v1.size, v2.size)
        
        for (i in 0 until maxLength) {
            val part1 = v1.getOrElse(i) { 0 }
            val part2 = v2.getOrElse(i) { 0 }
            
            when {
                part1 > part2 -> return 1
                part1 < part2 -> return -1
            }
        }
        
        // Same numbers: a pre-release (1.1.0-beta.1) comes before its release (1.1.0).
        val pre1 = isPreRelease(version1)
        val pre2 = isPreRelease(version2)
        return when {
            pre1 && !pre2 -> -1
            !pre1 && pre2 -> 1
            else -> 0
        }
    }

    /** `v1.1.0-beta.1-debug` → [1, 1, 0]: the numbers before the first `-` (build suffixes too). */
    private fun parseVersion(version: String): List<Int> {
        return core(version)
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    private fun core(version: String): String = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').replace(Regex("[^0-9.]"), "")

    /** `-alpha` / `-beta` / `-rc` after the numbers; the build type suffix (`-debug`) does not count. */
    private fun isPreRelease(version: String): Boolean =
        version.substringAfter('-', "").split('-').any { part -> part.isNotEmpty() && !part.equals("debug", ignoreCase = true) }
}
