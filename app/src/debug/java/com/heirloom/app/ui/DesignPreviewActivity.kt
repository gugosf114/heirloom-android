package com.heirloom.app.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult
import com.heirloom.app.ui.theater.RestorationTheater
import com.heirloom.app.ui.theme.HeirloomTheme

/**
 * Debug-only state harness for real-device visual review. It is not present in
 * release builds and cannot alter the production restoration workflow.
 */
class DesignPreviewActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("state")) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = intent.getStringExtra("state") ?: "selected"
        val image = intent.getStringExtra("image")
            ?: "android.resource://${packageName}/${com.heirloom.app.R.drawable.archive_portrait}"
        val restored = intent.getStringExtra("restored")
            ?: "android.resource://${packageName}/${com.heirloom.app.R.drawable.restored_sample}"
        val completed = mapOf(
            Stage.Uploading to StageResult.Completed,
            Stage.RepairingDamage to StageResult.Completed,
            Stage.RestoringFaces to StageResult.Completed,
            Stage.Upscaling to StageResult.Completed,
            Stage.CheckingIdentity to StageResult.Completed,
            Stage.Colorizing to StageResult.NotNeeded,
            Stage.Finalizing to StageResult.Completed,
        )

        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, intent.getFloatExtra("fontScale", 1f))) {
            HeirloomTheme(darkTheme = !intent.getBooleanExtra("light", false)) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            HeirloomBrandHeader(creditsRemaining = 3)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                when (state) {
                                    "idle" -> PremiumIdleBody(onPick = {}, onScan = {})
                                    "packs" -> { PremiumIdleBody(onPick = {}, onScan = {}); PaywallDialog(emptyList(), false, {}, {}, {}) }
                                    "processing" -> RestorationTheater(
                                        sourceUri = image,
                                        stage = Stage.RestoringFaces,
                                        stageResults = completed.filterKeys {
                                            it == Stage.Uploading || it == Stage.RepairingDamage
                                        },
                                    )
                                    "done" -> PremiumDoneBody(
                                        sourceUri = image,
                                        restoredUrl = restored,
                                        identityWarning = false,
                                        identityUnverified = false,
                                        wasColorized = false,
                                        cosineSimilarity = 0.94,
                                        elapsedSeconds = 74,
                                        stageResults = completed,
                                        onSave = {},
                                        onShare = {},
                                        onReport = {},
                                        onReset = {},
                                    )
                                    "warning" -> PremiumDoneBody(
                                        sourceUri = image,
                                        restoredUrl = restored,
                                        identityWarning = true,
                                        identityUnverified = false,
                                        wasColorized = true,
                                        cosineSimilarity = 0.61,
                                        elapsedSeconds = 91,
                                        stageResults = completed,
                                        onSave = {},
                                        onShare = {},
                                        onReport = {},
                                        onReset = {},
                                    )
                                    "error" -> PremiumFailedBody(
                                        message = "The connection was interrupted before the photograph finished restoring.",
                                        onRetry = {},
                                        onReset = {},
                                    )
                                    else -> PremiumPickedBody(
                                        sourceUri = image,
                                        onRestore = {},
                                        onReset = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
