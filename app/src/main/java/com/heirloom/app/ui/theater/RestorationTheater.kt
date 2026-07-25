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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
        "Sending your photograph",
        "A private copy is being sent securely to the restoration studio.",
    ),
    TheaterPhase(
        Stage.RepairingDamage,
        "Repairing age and damage",
        "Scratches, tears, stains, and faded areas are being carefully reconstructed.",
    ),
    TheaterPhase(
        Stage.RestoringFaces,
        "Restoring faces",
        "Facial detail is being recovered with identity-preserving restoration.",
    ),
    TheaterPhase(
        Stage.Upscaling,
        "Bringing back fine detail",
        "The photograph is being enlarged and cleaned without changing its character.",
    ),
    TheaterPhase(
        Stage.CheckingIdentity,
        "Checking identity",
        "The restored face is being compared with the original photograph.",
    ),
    TheaterPhase(
        Stage.Colorizing,
        "Finishing color",
        "Black-and-white photographs receive natural color; color photographs pass through unchanged.",
    ),
    TheaterPhase(
        Stage.Finalizing,
        "Preparing your copy",
        "The finished photograph is being prepared for saving and sharing.",
    ),
)

@Composable
fun RestorationTheater(
    sourceUri: String,
    stage: Stage,
    stageResults: Map<Stage, StageResult>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "RESTORATION STUDIO / LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Restoring your photograph",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "You can leave Heirloom. Restoration will continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        RestorationPhotoBench(
            sourceUri = sourceUri,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        RestorationStatusCard(
            stage = stage,
            stageResults = stageResults,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RestorationPhotoBench(
    sourceUri: String,
    modifier: Modifier = Modifier,
) {
    val beam by rememberInfiniteTransition(label = "restoration-light").animateFloat(
        initialValue = -0.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "restoration-light-sweep",
    )
    val brass = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawWithContent {
                    drawContent()
                    val y = size.height * beam
                    val trail = size.height * 0.2f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                brass.copy(alpha = 0.16f),
                            ),
                            startY = y - trail,
                            endY = y,
                        ),
                        topLeft = Offset(0f, y - trail),
                        size = size.copy(height = trail),
                    )
                    drawRect(
                        color = brass.copy(alpha = 0.72f),
                        topLeft = Offset(0f, y),
                        size = size.copy(height = 1.5.dp.toPx()),
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
}

@Composable
private fun RestorationStatusCard(
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

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "RESTORATION IN PROGRESS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "%d:%02d".format(elapsed / 60, elapsed % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = phase.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = phase.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            StageTrack(current = stageIndex, results = stageResults)
            Spacer(Modifier.height(9.dp))
            Text(
                text = "Step ${stageIndex + 1} of ${PHASES.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (elapsed > 90 && stage == Stage.Uploading) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "The restoration studio is starting up. Your photo is safe and the request is still connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StageTrack(
    current: Int,
    results: Map<Stage, StageResult>,
) {
    val pulse by rememberInfiniteTransition(label = "stage-pulse").animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stage-pulse-alpha",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PHASES.forEachIndexed { index, phase ->
            val result = results[phase.stage]
            val completed = result != null
            val muted = result == StageResult.Skipped || result == StageResult.NotNeeded
            val color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(7.dp)
                    .clip(CircleShape)
                    .then(
                        when {
                            completed -> Modifier.background(color)
                            index == current -> Modifier.background(color.copy(alpha = pulse))
                            else -> Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        },
                    ),
            )
        }
    }
}
