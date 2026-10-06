package com.cortinadev.dogmatix.util

/** What a quick-access entry point (a launcher shortcut, the Quick Settings tile) asks the app to do. */
enum class QuickAction(val key: String) {
    /** A random game of the library opens its details card. */
    SURPRISE("surprise"),
    /** The Downloads section. */
    DOWNLOADS("downloads"),
    /** The cursor goes into the library's search box. */
    SEARCH("search")
}

/**
 * The intent contract of the quick actions: an intent with action [ACTION_QUICK] and the action's
 * key in [EXTRA_QUICK_ACTION]. The extra is what counts (MainActivity removes it once handled, so
 * a rotation does not run it again); the action only has to be there because a shortcut intent
 * needs one. Pure JVM for the tests.
 */
object QuickActions {
    const val ACTION_QUICK = "com.cortinadev.dogmatix.action.QUICK"
    const val EXTRA_QUICK_ACTION = "com.cortinadev.dogmatix.QUICK_ACTION"

    /** The quick action an intent with this [action] and [extra] asks for, or null for any other intent. */
    fun parse(action: String?, extra: String?): QuickAction? {
        if (action != ACTION_QUICK) return null
        val key = extra?.trim()?.lowercase() ?: return null
        return QuickAction.values().firstOrNull { it.key == key }
    }
}
