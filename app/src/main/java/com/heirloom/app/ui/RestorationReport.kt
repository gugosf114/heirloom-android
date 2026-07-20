package com.heirloom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Restoration Report ─────────────────────────────────────────────────────
// The conservator's certificate. Every restore already produces this data —
// stages run, identity distance, colorization — the report is where it gets
// handed to the owner of the photograph instead of thrown away.

private class ReportRow(val label: String, val value: String, val warn: Boolean = false)

@Composable
fun RestorationReport(
    cosineSimilarity: Double?,
    identityWarning: Boolean,
    identityUnverified: Boolean,
    wasColorized: Boolean,
    elapsedSeconds: Long?,
    modifier: Modifier = Modifier,
) {
    val identity = when {
        identityWarning -> ReportRow("IDENTITY CHECK", "REVIEW ADVISED", warn = true)
        identityUnverified -> ReportRow("IDENTITY CHECK", "UNVERIFIED")
        cosineSimilarity != null -> ReportRow(
            "IDENTITY CHECK",
            "%.2f MATCH ✓".format(cosineSimilarity),
        )
        else -> ReportRow("IDENTITY CHECK", "—")
    }

    val rows = listOf(
        ReportRow("DAMAGE REPAIR", "✓"),
        ReportRow("FACE RECONSTRUCTION", "✓"),
        ReportRow("ENLARGEMENT 4X", "✓"),
        identity,
        ReportRow("COLORIZATION", if (wasColorized) "✓" else "NOT NEEDED"),
    )

    val completedOn = remember {
        SimpleDateFormat("MMM d yyyy", Locale.US).format(Date()).uppercase(Locale.US)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .padding(16.dp),
    ) {
        Text(
            text = "RESTORATION REPORT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))

        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.warn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))

        Text(
            text = buildString {
                append("COMPLETED $completedOn")
                elapsedSeconds?.let { append(" · ${it}S") }
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
