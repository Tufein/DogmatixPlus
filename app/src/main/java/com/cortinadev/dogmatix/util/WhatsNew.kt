package com.cortinadev.dogmatix.util

/** When to show what is new in this version: once after an update, never on a fresh install. */
object WhatsNew {
    /**
     * [lastSeen] is the version code whose notes were seen (0 = never), [current] the running one.
     * Before 3.5.0 nothing was stored, so 0 with the first-run setup done means an update.
     */
    fun shouldShow(lastSeen: Int, current: Int, onboarded: Boolean): Boolean =
        current > lastSeen && (lastSeen > 0 || onboarded)
}
