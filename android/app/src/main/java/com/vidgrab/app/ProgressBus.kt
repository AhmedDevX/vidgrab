package com.vidgrab.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Download progress published by DownloadService, collected by MainActivity. */
object ProgressBus {
    data class State(
        val running: Boolean,
        val percent: Float,
        val line: String,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    fun post(s: State) {
        _state.value = s
    }
}

/** Share-session state published by ShareServerService, collected by ShareActivity. */
object ShareBus {
    data class State(
        val running: Boolean,
        val url: String?,
        val fileCount: Int,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    fun post(s: State) {
        _state.value = s
    }
}
