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

/** What the restoration does. Travels app -> service -> gateway -> model prompt. */
enum class RestoreMode(val wire: String, val label: String, val blurb: String) {
    Full("full", "Full restore", "Repair damage, refine faces, sharpen detail."),
    Repair("repair", "Repair only", "Scratches, tears, stains, fading. Faces and softness untouched."),
    Sharpen("sharpen", "Sharpen only", "Faces and detail into focus. Damage marks left alone."),
    Color("color", "Color only", "Add color. Nothing else changes."),
}

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Picked(val source: Uri, val colorize: Boolean = false, val mode: RestoreMode = RestoreMode.Full) : RestoreState
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
    data class Failed(val source: Uri?, val message: String, val colorize: Boolean = false, val mode: RestoreMode = RestoreMode.Full) : RestoreState
}
