package com.heirloom.app.ui.theater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import kotlinx.coroutines.delay

// ─── Restoration Theater ────────────────────────────────────────────────────
// The wait IS the treatment. A conservator's bench under safelight: the
// user's own photograph on the table, a slow amber scan beam passing over it,
// and below, the treatment log ticking through the seven stages the pipeline
// actually runs. Runs independent of the API call — when the result lands,
// the parent swaps this out for the Done view.

private class TheaterPhase(
    val stage: Stage,
    val title: String,
    val logs: List<String>,
)

private val PHASES = listOf(
    TheaterPhase(
        Stage.Uploading, "RECEIVING ORIGINAL",
        listOf(
            "> reading scan · full resolution",
            "> transferring to the lab",
            "> original preserved untouched",
        ),
    ),
    TheaterPhase(
        Stage.RepairingDamage, "REPAIRING DAMAGE",
        listOf(
            "> mapping scratches and tears",
            "> filling emulsion cracks",
            "> lifting dust and spots",
        ),
    ),
    TheaterPhase(
        Stage.RestoringFaces, "RESTORING FACES",
        listOf(
            "> locating faces",
            "> rebuilding fine detail",
            "> preserving likeness",
        ),
    ),
    TheaterPhase(
        Stage.Upscaling, "ENLARGING",
        listOf(
            "> upscaling · 4x",
            "> sharpening edges",
            "> settling film grain",
        ),
    ),
    TheaterPhase(
        Stage.CheckingIdentity, "CHECKING IDENTITY",
        listOf(
            "> comparing against original",
            "> measuring likeness",
            "> confirming it is still them",
        ),
    ),
    TheaterPhase(
        Stage.Colorizing, "COLORIZING",
        listOf(
            "> testing period-accurate tones",
            "> tinting skin and fabric",
            "> holding true blacks",
        ),
    ),
    TheaterPhase(
        Stage.Finalizing, "FINAL INSPECTION",
        listOf(
            "> reviewing every region",
            "> preparing your copy",
        ),
    ),
)

private fun phaseFor(stage: Stage): TheaterPhase = PHASES.first { it.stage == stage }

@Composable
fun RestorationTheater(sourceUri: String, stage: Stage, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScanBench(
            sourceUri = sourceUri,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
        TreatmentLog(
            stage = stage,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// The photograph on the bench, dimmed, with the safelight beam passing over it.
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
                // Dim the artifact while it is being worked on.
                drawRect(Color.Black.copy(alpha = 0.45f))
                // Safelight beam: a bright line with a soft trail behind it.
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

// Stage counter, elapsed clock, typewriter title, pips, and the log rail.
@Composable
private fun TreatmentLog(stage: Stage, modifier: Modifier = Modifier) {
    val phase = phaseFor(stage)
    val stageIndex = PHASES.indexOfFirst { it.stage == stage }

    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsed++
        }
    }

    var typed by remember(stage) { mutableIntStateOf(0) }
    LaunchedEffect(stage) {
        typed = 0
        while (typed < phase.title.length) {
            delay(38)
            typed++
        }
    }

    var visibleLogs by remember(stage) { mutableIntStateOf(0) }
    LaunchedEffect(stage) {
        visibleLogs = 0
        repeat(phase.logs.size) {
            delay(1_000)
            visibleLogs++
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "STAGE %02d/%02d".format(stageIndex + 1, PHASES.size),
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
            text = phase.title.take(typed) + if (typed < phase.title.length) "▌" else "",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(12.dp))

        StagePips(current = stageIndex)

        Spacer(Modifier.height(14.dp))

        // Fixed-height rail so the panel doesn't jump as lines appear.
        Column(
            modifier = Modifier.height(96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            phase.logs.forEachIndexed { i, line ->
                AnimatedVisibility(
                    visible = i < visibleLogs,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == visibleLogs - 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (stage == Stage.Finalizing && visibleLogs >= phase.logs.size && elapsed > 50) {
                Text(
                    text = "> large scans take a little longer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StagePips(current: Int) {
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
        repeat(PHASES.size) { i ->
            val color = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .then(
                        when {
                            i < current -> Modifier.background(color)
                            i == current -> Modifier.background(color.copy(alpha = pulse))
                            else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
                        },
                    ),
            )
        }
    }
}
