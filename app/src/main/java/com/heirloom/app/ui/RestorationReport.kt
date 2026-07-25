package com.heirloom.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class ReportRow(
    val label: String,
    val value: String,
    val warn: Boolean = false,
)

@Composable
fun RestorationReport(
    cosineSimilarity: Double?,
    identityWarning: Boolean,
    identityUnverified: Boolean,
    wasColorized: Boolean,
    elapsedSeconds: Long?,
    stageResults: Map<Stage, StageResult>,
    modifier: Modifier = Modifier,
) {
    val identity = when {
        identityWarning -> ReportRow("Identity check", "Review advised", warn = true)
        identityUnverified -> ReportRow("Identity check", "Unavailable", warn = true)
        cosineSimilarity != null -> ReportRow(
            "Identity check",
            "${(cosineSimilarity * 100).coerceIn(0.0, 100.0).toInt()}% match",
        )
        else -> ReportRow("Identity check", "Not reported", warn = true)
    }

    fun stageRow(label: String, stage: Stage): ReportRow = when (stageResults[stage]) {
        StageResult.Completed -> ReportRow(label, "Completed")
        StageResult.NotNeeded -> ReportRow(label, "Not needed")
        StageResult.Skipped -> ReportRow(label, "Skipped", warn = true)
        null -> ReportRow(label, "Not reported", warn = true)
    }

    val rows = listOf(
        stageRow("Damage repair", Stage.RepairingDamage),
        stageRow("Face restoration", Stage.RestoringFaces),
        stageRow("Fine-detail enlargement", Stage.Upscaling),
        identity,
        when {
            wasColorized -> ReportRow("Colorization", "Completed")
            stageResults[Stage.Colorizing] == StageResult.NotNeeded ->
                ReportRow("Colorization", "Not needed")
            else -> stageRow("Colorization", Stage.Colorizing)
        },
    )

    val completedOn = remember {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "ARCHIVAL RECORD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Restoration record",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "A clear record of what Heirloom changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (row.warn) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.size(9.dp))
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.warn) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(11.dp))
            Text(
                text = buildString {
                    append("Completed $completedOn")
                    elapsedSeconds?.let {
                        append(" · ")
                        append(
                            if (it >= 60) {
                                "${it / 60}m ${it % 60}s"
                            } else {
                                "${it}s"
                            },
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
