package com.heirloom.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    private var stageRotator: Job? = null

    fun pickPhoto(uri: Uri) {
        _state.value = RestoreState.Picked(uri)
    }

    fun reset() {
        stageRotator?.cancel()
        _state.value = RestoreState.Idle
    }

    fun startRestoration() {
        val current = _state.value
        val source = when (current) {
            is RestoreState.Picked -> current.source
            is RestoreState.Failed -> current.source ?: return
            else -> return
        }
        _state.value = RestoreState.Processing(source, Stage.Uploading)

        stageRotator = viewModelScope.launch {
            // Calibrated rotation. Real wallclock per stage on Replicate
            // varies, but the order is fixed. We hold on Finalizing if the
            // server takes longer than the cumulative budget.
            val schedule = listOf(
                Stage.Uploading to 4_000L,
                Stage.RepairingDamage to 18_000L,
                Stage.RestoringFaces to 14_000L,
                Stage.Upscaling to 18_000L,
                Stage.CheckingIdentity to 6_000L,
                Stage.Colorizing to 12_000L,
                Stage.Finalizing to Long.MAX_VALUE,
            )
            for ((stage, hold) in schedule) {
                _state.value = RestoreState.Processing(source, stage)
                if (hold == Long.MAX_VALUE) break
                delay(hold)
            }
        }

        viewModelScope.launch {
            try {
                // TODO: pass real Firebase ID token once auth is wired.
                val result = RestoreApi.restore(getApplication(), source, idToken = null)
                stageRotator?.cancel()
                _state.value = RestoreState.Done(
                    source = source,
                    restoredUrl = result.restoredUrl,
                    cosineSimilarity = result.cosineSimilarity,
                    identityWarning = result.identityWarning,
                    wasColorized = result.wasColorized,
                )
            } catch (t: Throwable) {
                stageRotator?.cancel()
                _state.value = RestoreState.Failed(source, t.message ?: "Unknown error")
            }
        }
    }
}
