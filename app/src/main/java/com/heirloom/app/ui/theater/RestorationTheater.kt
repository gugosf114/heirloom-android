package com.heirloom.app.ui.theater

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult
import kotlinx.coroutines.delay

private data class TheaterPhase(
    val stage: Stage,
    val title: String,
    val note: String,
)

private val PHASES = listOf(
    TheaterPhase(
        Stage.Uploading,
        "CONTACTING RESTORATION LAB",
        "Starting the encrypted upload. The original remains untouched.",
    ),
    TheaterPhase(
        Stage.RepairingDamage,
        "REPAIRING DAMAGE",
        "The scratch-and-tear repair stage is running now.",
    ),
    TheaterPhase(
        Stage.RestoringFaces,
        "RESTORING FACES",
        "The face-restoration stage is running at identity-preserving fidelity.",
    ),
    TheaterPhase(
        Stage.Upscaling,
        "ENLARGING DETAIL",
        "The enlargement and denoising stage is running now.",
    ),
    TheaterPhase(
        Stage.CheckingIdentity,
        "CHECKING IDENTITY",
        "The restored face is being compared with the original.",
    ),
    TheaterPhase(
        Stage.Colorizing,
        "CHECKING COLOR",
        "Black-and-white photos are colorized; color photos pass through unchanged.",
    ),
    TheaterPhase(
        Stage.Finalizing,
        "PREPARING YOUR COPY",
        "The finished image is being saved on this device.",
    ),
)

@Composable
fun RestorationTheater(
    sourceUri: String,
    stage: Stage,
    stageResults: Map<Stage, StageResult>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScanBench(
            sourceUri = sourceUri,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
        LiveTreatmentStatus(
            stage = stage,
            stageResults = stageResults,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScanBench(sourceUri: String, modifier: Modifier = Modifier) {
    val beam by rememberInfiniteTransition(label = "beam").animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beam-sweep",
    )
    val amber = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.45f))
                val y = size.height * beam
                val trail = size.height * 0.22f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, amber.copy(alpha = 0.28f)),
                        startY = y - trail,
                        endY = y,
                    ),
                    topLeft = Offset(0f, y - trail),
                    size = size.copy(height = trail),
                )
                drawRect(
                    color = amber.copy(alpha = 0.9f),
                    topLeft = Offset(0f, y),
                    size = size.copy(height = 3.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = sourceUri,
            contentDescription = "Photo being restored",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LiveTreatmentStatus(
    stage: Stage,
    stageResults: Map<Stage, StageResult>,
    modifier: Modifier = Modifier,
) {
    val phase = PHASES.first { it.stage == stage }
    val stageIndex = PHASES.indexOfFirst { it.stage == stage }
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsed++
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "LIVE · CLOUD RUN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "%02d:%02d".format(elapsed / 60, elapsed % 60),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = phase.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        StagePips(current = stageIndex, results = stageResults)
        Spacer(Modifier.height(12.dp))
        Text(
            text = phase.note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (elapsed > 90 && stage == Stage.Uploading) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The GPU is waking from zero. Your request is still connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagePips(current: Int, results: Map<Stage, StageResult>) {
    val pulse by rememberInfiniteTransition(label = "pip").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pip-pulse",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PHASES.forEachIndexed { index, phase ->
            val result = results[phase.stage]
            val color = when (result) {
                StageResult.Skipped, StageResult.NotNeeded ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .then(
                        when {
                            result != null -> Modifier.background(color)
                            index == current -> Modifier.background(color.copy(alpha = pulse))
                            else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
                        },
                    ),
            )
        }
    }
}
