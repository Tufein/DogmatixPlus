package com.tufein.valkyr.util

/**
 * The "Use RomM" master switch (6.x fix 54). While it is off the settings repository hands out a blank RomM
 * address and token, so every "is RomM set up?" check says no and every RomM call stops at its first line;
 * the saved values are never touched, so switching back on resumes where it was.
 */
object RommSwitch {
    /** [saved] while RomM is [enabled], blank otherwise. */
    fun effective(enabled: Boolean, saved: String): String = if (enabled) saved else ""

    /** True when a library row that came from RomM should still be listed: RomM is on, or the file is on the device. */
    fun listRow(enabled: Boolean, fromRomm: Boolean, onDevice: Boolean): Boolean = enabled || !fromRomm || onDevice
}
