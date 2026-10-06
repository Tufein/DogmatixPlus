package com.cortinadev.dogmatix.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps moves of game folders and downloads apart (8.0):
 *  - [lock]: only one mover at a time, "Move the library" ([LibraryMoveService]) or smart storage
 *    ([SmartStorageService]); the other one reports that a move is already running;
 *  - per console: while smart storage moves a console's folder, a download for that console waits
 *    before it picks its target folder ([awaitFree]), so nothing is written into a folder that is
 *    being copied away, and it lands in the console's new folder afterwards.
 */
@Singleton
class StorageMoveGate @Inject constructor() {
    val lock = Mutex()

    private val _moving = MutableStateFlow<Set<String>>(emptySet())
    /** Consoles whose folder is being moved right now. */
    val moving: StateFlow<Set<String>> = _moving.asStateFlow()

    fun hold(consoleId: String) = _moving.update { it + consoleId }

    fun release(consoleId: String) = _moving.update { it - consoleId }

    /** Returns at once unless [consoleId]'s folder is being moved; then waits until it is done. */
    suspend fun awaitFree(consoleId: String) {
        if (consoleId !in _moving.value && "*" !in _moving.value) return
        _moving.first { consoleId !in it && "*" !in it }
    }
}
