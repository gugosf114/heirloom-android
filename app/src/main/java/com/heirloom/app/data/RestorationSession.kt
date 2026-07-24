package com.heirloom.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide restoration state shared by the foreground service and UI.
 *
 * The restoration is deliberately not owned by a screen or ViewModel: Android
 * may stop screen work as soon as Heirloom is backgrounded.
 */
object RestorationSession {
    private val mutableState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = mutableState.asStateFlow()

    fun update(state: RestoreState) {
        mutableState.value = state
    }
}
