package com.heirloom.app.data

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    fun pickPhoto(uri: Uri) {
        _state.value = RestoreState.Picked(uri)
    }

    fun photoCropped(uri: Uri) {
        _state.value = RestoreState.Picked(uri)
    }

    fun reset() {
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

        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val stageResults = linkedMapOf<Stage, StageResult>()
            try {
                val result = RestoreApi.restore(getApplication(), source) { event ->
                    when (event) {
                        is PipelineEvent.StageStarted -> {
                            stageResults.putIfAbsent(Stage.Uploading, StageResult.Completed)
                            _state.value = RestoreState.Processing(
                                source,
                                event.stage,
                                stageResults.toMap(),
                            )
                        }
                        is PipelineEvent.StageFinished -> {
                            stageResults[event.stage] = event.result
                            _state.value = RestoreState.Processing(
                                source,
                                event.stage,
                                stageResults.toMap(),
                            )
                        }
                        is PipelineEvent.Final -> {
                            _state.value = RestoreState.Processing(
                                source,
                                Stage.Finalizing,
                                stageResults.toMap(),
                            )
                        }
                        else -> Unit
                    }
                }
                _state.value = RestoreState.Done(
                    source = source,
                    restoredUrl = result.restoredUrl,
                    cosineSimilarity = result.cosineSimilarity,
                    identityWarning = result.identityWarning,
                    wasColorized = result.wasColorized,
                    identityUnverified = result.identityUnverified,
                    elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1_000,
                    stageResults = stageResults.toMap(),
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("HeirloomRestore", "Restoration failed", t)
                _state.value = RestoreState.Failed(source, userMessage(t))
            }
        }
    }

    suspend fun reportResult(
        done: RestoreState.Done,
        reason: String,
        details: String,
    ): Result<Unit> = runCatching {
        RestoreApi.report(
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

    private fun userMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "No internet connection. Reconnect and try again."
        is SocketTimeoutException -> "The restoration took too long. Please try again."
        is RestoreHttpException -> when (error.statusCode) {
            401 -> "This installation needs to be refreshed. Please update Heirloom."
            429 -> "The restoration lab is busy. Try again in a moment."
            503 -> "The restoration lab is warming up. Try again in a moment."
            else -> "The restoration could not finish. Please try again."
        }
        else -> "The restoration could not finish. Please try again."
    }
}
