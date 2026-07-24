package com.heirloom.app.data

import org.json.JSONObject

data class PipelineFinal(
    val restoredUrl: String,
    val cosineSimilarity: Double?,
    val identityWarning: Boolean,
    val wasColorized: Boolean,
    val identityUnverified: Boolean,
)

sealed interface PipelineEvent {
    data class StageStarted(val stage: Stage) : PipelineEvent
    data class StageFinished(val stage: Stage, val result: StageResult) : PipelineEvent
    data class Final(val result: PipelineFinal) : PipelineEvent
    data class Error(val message: String) : PipelineEvent
    data object Ignored : PipelineEvent
}

fun parsePipelineEvent(line: String): PipelineEvent {
    val json = JSONObject(line)
    return when (json.optString("kind")) {
        "stage_start" -> backendStage(json.optString("stage"))
            ?.let { PipelineEvent.StageStarted(it) }
            ?: PipelineEvent.Ignored

        "stage_done" -> {
            val stage = backendStage(json.optString("stage")) ?: return PipelineEvent.Ignored
            val skipped = json.optJSONObject("extra")?.optBoolean("skipped", false) == true
            PipelineEvent.StageFinished(
                stage,
                if (skipped) StageResult.Skipped else StageResult.Completed,
            )
        }

        "stage_skipped" -> {
            val stage = backendStage(json.optString("stage")) ?: return PipelineEvent.Ignored
            PipelineEvent.StageFinished(
                stage,
                if (stage == Stage.Colorizing) StageResult.NotNeeded else StageResult.Skipped,
            )
        }

        "final" -> {
            val result = json.getJSONObject("result")
            val cosine = result
                .takeIf { it.has("cosine_similarity") && !it.isNull("cosine_similarity") }
                ?.getDouble("cosine_similarity")
            val skipped = result.optBoolean("adaface_skipped", false)
            PipelineEvent.Final(
                PipelineFinal(
                    restoredUrl = result.getString("restored_url"),
                    cosineSimilarity = cosine,
                    identityWarning = result.optBoolean("identity_warning", false),
                    wasColorized = result.optBoolean("was_colorized", false),
                    identityUnverified = skipped || cosine == null,
                ),
            )
        }

        "error" -> PipelineEvent.Error(
            json.optString("message").ifBlank { "restoration could not finish" },
        )

        else -> PipelineEvent.Ignored
    }
}

private fun backendStage(name: String): Stage? = when (name) {
    "bopb" -> Stage.RepairingDamage
    "codeformer" -> Stage.RestoringFaces
    "esrgan" -> Stage.Upscaling
    "adaface" -> Stage.CheckingIdentity
    "ddcolor" -> Stage.Colorizing
    else -> null
}
