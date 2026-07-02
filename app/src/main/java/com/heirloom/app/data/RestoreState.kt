package com.heirloom.app.data

import android.net.Uri

/**
 * Stages roughly correspond to the Worker's pipeline. Client rotates through
 * them on a timer while the request is in flight — server doesn't stream
 * progress events in v1, so this is a calibrated approximation, not a lie.
 * Order matches the actual Replicate call order on the Worker.
 */
enum class Stage {
    Uploading,
    RepairingDamage,
    RestoringFaces,
    Upscaling,
    CheckingIdentity,
    Colorizing,
    Finalizing,
}

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Picked(val source: Uri) : RestoreState
    data class Processing(val source: Uri, val stage: Stage) : RestoreState
    data class Done(
        val source: Uri,
        val restoredUrl: String,
        val cosineSimilarity: Double?,
        val identityWarning: Boolean,
        val wasColorized: Boolean,
        val identityUnverified: Boolean,
    ) : RestoreState
    data class Failed(val source: Uri?, val message: String) : RestoreState
}
