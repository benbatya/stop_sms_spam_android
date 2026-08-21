package com.batya.stopsmsspam.bulk

import com.batya.stopsmsspam.data.model.BatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide view of the running batch.
 *
 * The service owns the sending and the UI only observes, so progress lives here rather than in a
 * ViewModel - the screen can be destroyed and recreated mid-batch without the run noticing.
 */
object BulkReplyController {

    private val _progress = MutableStateFlow(BatchProgress())
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()

    fun set(progress: BatchProgress) {
        _progress.value = progress
    }

    fun update(transform: (BatchProgress) -> BatchProgress) {
        _progress.update(transform)
    }

    fun reset() {
        _progress.value = BatchProgress()
    }
}
