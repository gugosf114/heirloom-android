package com.heirloom.app.ui.theater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult
import com.heirloom.app.ui.GalleryEyebrow
import com.heirloom.app.ui.PremiumPhotoMount

private val phases = listOf(
    Stage.Uploading to "Sending your photograph",
    Stage.RepairingDamage to "Repairing damage",
    Stage.RestoringFaces to "Restoring faces",
    Stage.Upscaling to "Refining the detail",
    Stage.Colorizing to "Working on color",
    Stage.CheckingIdentity to "Comparing the face",
    Stage.Finalizing to "Preparing your copy",
)

@Composable
fun RestorationTheater(sourceUri: String, stage: Stage, stageResults: Map<Stage, StageResult>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 20.dp)) {
        GalleryEyebrow("02 / In the studio")
        Text("Care takes a moment.", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 9.dp, bottom = 6.dp))
        Text("You can leave the app while we work.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        PremiumPhotoMount(sourceUri, "Your photograph being restored", Modifier.fillMaxWidth().height(230.dp))
        Row(Modifier.padding(top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
            Text(phases.first { it.first == stage }.second, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        phases.forEachIndexed { index, (step, title) ->
            val result = stageResults[step]
            val active = stage == step && result == null
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("0${index + 1}", style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = if (active || result == StageResult.Completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                when (result) {
                    StageResult.Completed -> Icon(Icons.Outlined.Check, "Complete", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.secondary)
                    StageResult.Skipped -> Text("Skipped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    StageResult.NotNeeded -> Text("Not needed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> if (active) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
}
