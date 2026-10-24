package com.tufein.valkyr.util

/**
 * The save slots of one game on the device: RetroArch keeps a save state per slot as
 * `Game.state` (slot 0), `Game.state1` … `Game.stateN` and `Game.state.auto`. The game's view shows
 * the slots on one card and can copy one slot into another (an empty one or one that is replaced;
 * the plan is shown first, and the slot it replaces is backed up before anything is written).
 * Pure Kotlin for the tests.
 */
object SaveSlots {

    /** RetroArch's slot numbers go higher, but the card offers the ones people use. */
    const val MAX_SLOT = 99

    private val slotName = Regex("""^(.+)\.state(?:(\d+)|\.(auto))?$""", RegexOption.IGNORE_CASE)

    /** One state file: [index] is its slot number (null for the auto slot). */
    data class Slot(val file: LocalSaveFile, val base: String, val index: Int?) {
        val auto: Boolean get() = index == null
    }

    /** The slots of one game in one folder, auto slot last. */
    data class Group(val base: String, val slots: List<Slot>) {
        /** The smallest slot number nobody uses yet, or null when 0..[MAX_SLOT] are all taken. */
        val firstFree: Int? get() = (0..MAX_SLOT).firstOrNull { n -> slots.none { it.index == n } }
    }

    /** The slot a device file is, or null when it is not a plain RetroArch-style state. */
    fun parse(file: LocalSaveFile): Slot? {
        if (file.kind != SaveKind.STATE || file.layout != SaveFolderLayout.FILES) return null
        val m = slotName.matchEntire(file.path) ?: return null
        val number = m.groupValues[2]
        val index = when {
            m.groupValues[3].isNotEmpty() -> null
            number.isEmpty() -> 0
            else -> number.toIntOrNull()?.takeIf { it in 0..MAX_SLOT } ?: return null
        }
        return Slot(file, m.groupValues[1], index)
    }

    /** Games with two or more slots, each as one group (a lone state is not a "several slots" case). */
    fun groups(files: List<LocalSaveFile>): List<Group> =
        files.mapNotNull(::parse).groupBy { it.base.lowercase() }.values
            .map { slots -> Group(slots.first().base, slots.sortedWith(compareBy({ it.auto }, { it.index ?: Int.MAX_VALUE }))) }
            .filter { it.slots.size >= 2 }
            .sortedBy { it.base.lowercase() }

    /** The file name of slot [index] below [base]. */
    fun pathOf(base: String, index: Int): String = if (index == 0) "$base.state" else "$base.state$index"

    /** A slot the copy may go into: an existing one (it is replaced) or a new number. */
    data class Target(val index: Int, val path: String, val existing: LocalSaveFile?)

    /** The slots [source] can be copied into: every other numbered slot of the game plus the first free number. The auto slot is never a target (the emulator rewrites it). */
    fun targets(group: Group, source: Slot): List<Target> {
        val existing = group.slots.filter { it.index != null && it.index != source.index }
            .map { Target(it.index!!, it.file.path, it.file) }
        val free = group.firstFree?.takeIf { it != source.index }?.let { Target(it, pathOf(group.base, it), null) }
        return (existing + listOfNotNull(free)).sortedBy { it.index }
    }

    /** What a copy would do, for the preview. */
    data class CopyPlan(val source: LocalSaveFile, val targetPath: String, val replaces: LocalSaveFile?)

    fun plan(source: Slot, target: Target): CopyPlan? {
        if (source.file.path.equals(target.path, ignoreCase = true)) return null
        return CopyPlan(source.file, target.path, target.existing)
    }

    /** Whether [targetPath] is a numbered slot of the same game and folder as [sourcePath] (the service checks this before it writes). */
    fun sameGame(sourcePath: String, targetPath: String): Boolean {
        val a = slotName.matchEntire(sourcePath) ?: return false
        val b = slotName.matchEntire(targetPath) ?: return false
        return a.groupValues[1].equals(b.groupValues[1], ignoreCase = true) && b.groupValues[3].isEmpty()
    }
}
