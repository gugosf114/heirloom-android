package com.heirloom.app.data

import android.net.Uri

/** User-facing stages driven by real Cloud Run NDJSON events. */
enum class Stage {
    Uploading,
    RepairingDamage,
    RestoringFaces,
    Upscaling,
    CheckingIdentity,
    Colorizing,
    Finalizing,
}

enum class StageResult {
    Completed,
    Skipped,
    NotNeeded,
}

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Picked(val source: Uri) : RestoreState
    data class Processing(
        val source: Uri,
        val stage: Stage,
        val stageResults: Map<Stage, StageResult> = emptyMap(),
    ) : RestoreState
    data class Done(
        val source: Uri,
        val restoredUrl: String,
        val cosineSimilarity: Double?,
        val identityWarning: Boolean,
        val wasColorized: Boolean,
        val identityUnverified: Boolean,
        val elapsedSeconds: Long? = null,
        val stageResults: Map<Stage, StageResult> = emptyMap(),
    ) : RestoreState
    data class Failed(val source: Uri?, val message: String) : RestoreState
}
