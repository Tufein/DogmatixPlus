package com.cortinadev.dogmatix.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The download slots: at most [slots] downloads run at once, and the ones waiting go in the
 * order of [waiting], which the user can change (move up / down / to the front). A plain
 * semaphore would always serve the oldest first.
 */
class DownloadQueue(slots: Int) {
    private data class State(val slots: Int, val running: Int, val waiting: List<String>)

    private val state = MutableStateFlow(State(slots.coerceAtLeast(1), 0, emptyList()))

    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    /** Waiting downloads, first to start first. */
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    fun setSlots(slots: Int) { state.update { it.copy(slots = slots.coerceAtLeast(1)) }; publish() }

    /** Suspends until [name] may start; call [release] when it is done (also when it failed). */
    suspend fun acquire(name: String) {
        state.update { if (name in it.waiting) it else it.copy(waiting = it.waiting + name) }
        publish()
        try {
            while (true) {
                var granted = false
                state.update { s ->
                    if (s.running < s.slots && s.waiting.firstOrNull() == name) {
                        granted = true
                        s.copy(running = s.running + 1, waiting = s.waiting.drop(1))
                    } else s
                }
                if (granted) { publish(); return }
                val seen = state.value
                state.first { it != seen }
            }
        } catch (e: Throwable) {
            state.update { it.copy(waiting = it.waiting - name) }
            publish()
            throw e
        }
    }

    fun release() {
        state.update { it.copy(running = (it.running - 1).coerceAtLeast(0)) }
        publish()
    }

    fun moveUp(name: String) = reorder(name) { list, i -> if (i > 0) list.swap(i, i - 1) else list }
    fun moveDown(name: String) = reorder(name) { list, i -> if (i < list.lastIndex) list.swap(i, i + 1) else list }
    fun moveToFront(name: String) = reorder(name) { list, i -> listOf(list[i]) + list.filterIndexed { j, _ -> j != i } }

    private fun reorder(name: String, change: (List<String>, Int) -> List<String>) {
        state.update { s ->
            val i = s.waiting.indexOf(name)
            if (i < 0) s else s.copy(waiting = change(s.waiting, i))
        }
        publish()
    }

    private fun List<String>.swap(a: Int, b: Int): List<String> = toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

    private fun publish() { _waiting.value = state.value.waiting }
}
