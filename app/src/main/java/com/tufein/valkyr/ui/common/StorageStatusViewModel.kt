package com.tufein.valkyr.ui.common

import androidx.lifecycle.ViewModel
import com.tufein.valkyr.data.service.LibraryIndexService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Free space of the download volume, for the shell's status strip. */
@HiltViewModel
class StorageStatusViewModel @Inject constructor(
    libraryIndex: LibraryIndexService
) : ViewModel() {
    val freeBytes: StateFlow<Long?> = libraryIndex.freeBytes
}
