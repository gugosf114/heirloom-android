package com.heirloom.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineEventParserTest {
    @Test
    fun mapsRealBackendStages() {
        assertEquals(
            PipelineEvent.StageStarted(Stage.RepairingDamage),
            parsePipelineEvent("""{"kind":"stage_start","stage":"bopb"}"""),
        )
        assertEquals(
            PipelineEvent.StageFinished(Stage.RestoringFaces, StageResult.Completed),
            parsePipelineEvent("""{"kind":"stage_done","stage":"codeformer","extra":{}}"""),
        )
        assertEquals(
            PipelineEvent.StageFinished(Stage.Upscaling, StageResult.Skipped),
            parsePipelineEvent(
                """{"kind":"stage_done","stage":"esrgan","extra":{"skipped":true}}""",
            ),
        )
    }

    @Test
    fun colorInputMarksColorizationNotNeeded() {
        assertEquals(
            PipelineEvent.StageFinished(Stage.Colorizing, StageResult.NotNeeded),
            parsePipelineEvent("""{"kind":"stage_skipped","stage":"ddcolor"}"""),
        )
    }

    @Test
    fun finalCarriesIdentityAndColorizationEvidence() {
        val event = parsePipelineEvent(
            """
            {
              "kind":"final",
              "result":{
                "restored_url":"data:image/jpeg;base64,AA==",
                "cosine_similarity":0.91,
                "identity_warning":false,
                "was_colorized":true,
                "adaface_skipped":false
              }
            }
            """.trimIndent(),
        ) as PipelineEvent.Final

        assertEquals(0.91, event.result.cosineSimilarity!!, 0.001)
        assertFalse(event.result.identityWarning)
        assertFalse(event.result.identityUnverified)
        assertTrue(event.result.wasColorized)
    }

    @Test
    fun unavailableIdentityCheckIsUnverified() {
        val event = parsePipelineEvent(
            """
            {
              "kind":"final",
              "result":{
                "restored_url":"data:image/jpeg;base64,AA==",
                "cosine_similarity":null,
                "identity_warning":true,
                "was_colorized":false,
                "adaface_skipped":true
              }
            }
            """.trimIndent(),
        ) as PipelineEvent.Final

        assertTrue(event.result.identityUnverified)
    }
}
