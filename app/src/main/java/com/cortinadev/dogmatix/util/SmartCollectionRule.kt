package com.cortinadev.dogmatix.util

data class SmartCollectionRule(
    @field:com.google.gson.annotations.SerializedName("consoles") val consoles: Set<String> = emptySet(), @field:com.google.gson.annotations.SerializedName("languages") val languages: Set<String> = emptySet(),
    @field:com.google.gson.annotations.SerializedName("genre") val genre: String = "", @field:com.google.gson.annotations.SerializedName("fromYear") val fromYear: Int? = null, @field:com.google.gson.annotations.SerializedName("toYear") val toYear: Int? = null,
    @field:com.google.gson.annotations.SerializedName("played") val played: String = "ANY"
) {
    val valid: Boolean get() = (fromYear == null || fromYear in 1900..2100) &&
        (toYear == null || toYear in 1900..2100) && (fromYear == null || toYear == null || fromYear <= toYear) &&
        played in setOf("ANY", "PLAYED", "NEVER")
    fun matches(console: String, tags: Collection<String>, genres: String?, year: Int?, wasPlayed: Boolean): Boolean =
        valid && (consoles.isEmpty() || console in consoles) &&
        (languages.isEmpty() || tags.any { tag -> languages.any { it.equals(tag, true) } }) &&
        (genre.isBlank() || genres?.split('|', ',', ';')?.any { it.trim().equals(genre.trim(), true) } == true) &&
        (fromYear == null || year != null && year >= fromYear) &&
        (toYear == null || year != null && year <= toYear) &&
        (played == "ANY" || wasPlayed == (played == "PLAYED"))
}
