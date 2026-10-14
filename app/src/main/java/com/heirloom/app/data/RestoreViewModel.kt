package com.heirloom.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heirloom.app.HeirloomApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AnimationState {
    data object Idle : AnimationState
    data object Working : AnimationState
    data class Done(val videoUrl: String, val seconds: Int) : AnimationState
    data class Failed(val message: String) : AnimationState
}

class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<RestoreState> = RestorationSession.state
    private val _animation = MutableStateFlow<AnimationState>(AnimationState.Idle)
    val animation: StateFlow<AnimationState> = _animation.asStateFlow()

    /** "Bring it to life": one short clip from the restored photo. Spends one credit. */
    fun animate(done: RestoreState.Done) {
        if (_animation.value is AnimationState.Working) return
        _animation.value = AnimationState.Working
        viewModelScope.launch {
            runCatching { RestoreApi.animate(getApplication(), done.restoredUrl) }
                .onSuccess { result ->
                    (getApplication<Application>() as HeirloomApp).billing.recordSuccessfulRestoration(result.creditsRemaining)
                    _animation.value = AnimationState.Done(result.videoUrl, result.seconds)
                }
                .onFailure { error ->
                    _animation.value = AnimationState.Failed(
                        when ((error as? RestoreHttpException)?.statusCode) {
                            402 -> "Choose a restoration pack to bring photos to life."
                            401 -> "Access expired. Reopen Heirloom and try again."
                            else -> "The clip could not be made. The photo may be famous, or the lab is busy. Your credit is returned."
                        },
                    )
                }
        }
    }

    fun clearAnimation() { _animation.value = AnimationState.Idle }

    fun pickPhoto(uri: Uri) {
        RestorationSession.update(RestoreState.Picked(uri))
    }

    fun photoCropped(uri: Uri) {
        RestorationSession.update(RestoreState.Picked(uri))
    }

    fun setColorize(enabled: Boolean) {
        val current = state.value as? RestoreState.Picked ?: return
        RestorationSession.update(current.copy(colorize = enabled))
    }

    fun reset() {
        _animation.value = AnimationState.Idle
        RestorationSession.update(RestoreState.Idle)
    }

    fun startRestoration() {
        val current = state.value
        val source = when (current) {
            is RestoreState.Picked -> current.source
            is RestoreState.Failed -> current.source ?: return
            else -> return
        }
        val colorize = when (current) {
            is RestoreState.Picked -> current.colorize
            is RestoreState.Failed -> current.colorize
            else -> false
        }
        RestorationSession.update(RestoreState.Processing(source, Stage.Uploading))
        runCatching {
            RestorationService.start(getApplication(), source, colorize)
        }.onFailure {
            RestorationSession.update(
                RestoreState.Failed(source, "The restoration could not start. Please try again.", colorize),
            )
        }
    }

    suspend fun reportResult(
        done: RestoreState.Done,
        reason: String,
        details: String,
    ): Result<Unit> = runCatching {
        RestoreApi.report(
            getApplication(),
            ResultReport(
                reason = reason,
                details = details,
                cosineSimilarity = done.cosineSimilarity,
                identityWarning = done.identityWarning,
                identityUnverified = done.identityUnverified,
                wasColorized = done.wasColorized,
            ),
        )
    }

}
