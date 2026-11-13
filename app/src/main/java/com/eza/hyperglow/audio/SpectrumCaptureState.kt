package com.eza.hyperglow.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App 进程内的回放捕获状态，供设置页展示与开关。 */
object SpectrumCaptureState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    internal fun setError(value: String?) {
        _error.value = value
    }
}
