package com.cortinadev.dogmatix.util

/**
 * Reads a "done / total" progress text (as the save sync reports it, "3 / 12") back into a
 * fraction for a progress bar.
 */
object ProgressText {
    private val pattern = Regex("""(\d+)\s*/\s*(\d+)""")

    /** 0..1, or null when [text] holds no usable "done / total" (missing, malformed, total 0). */
    fun fraction(text: String?): Float? {
        val match = text?.let { pattern.find(it) } ?: return null
        val done = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull() ?: return null
        if (total <= 0L) return null
        return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
