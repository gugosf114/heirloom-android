package com.heirloom.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<RestoreState> = RestorationSession.state

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
