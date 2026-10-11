package com.heirloom.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.heirloom.app.R
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult

@Composable
internal fun HeirloomBrandHeader(
    creditsRemaining: Int?, modifier: Modifier = Modifier, onCredits: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_heirloom_logo), null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text("heirloom", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp), letterSpacing = (-0.6).sp)
            if (creditsRemaining != null) {
                Surface(onClick = { onCredits?.invoke() }, enabled = onCredits != null,
                    shape = RoundedCornerShape(6.dp), color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$creditsRemaining", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("  left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("PHOTO\nRESTORATION", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun GalleryEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun PremiumIdleBody(onPick: () -> Unit, onScan: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val photoHeight = (maxHeight * 0.39f).coerceIn(210.dp, 340.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 16.dp)) {
            GalleryEyebrow("A little care. A little time travel.")
            Text("A second life\nfor old photos.", style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
            Box(Modifier.fillMaxWidth().height(photoHeight)) {
                // A real public-domain archive photograph, not a fabricated restoration result.
                Box(Modifier.fillMaxSize().padding(start = 26.dp, top = 6.dp, end = 10.dp, bottom = 12.dp)
                    .rotate(3f).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)))
                Row(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 4.dp)
                    .shadow(12.dp, RoundedCornerShape(2.dp)).background(Color(0xFFECE4D6)).padding(9.dp)) {
                    Image(painterResource(R.drawable.archive_portrait),
                        "Migrant Mother, a 1936 photograph by Dorothea Lange",
                        modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                    Column(Modifier.width(30.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("01", color = Color(0xFF43514C), style = MaterialTheme.typography.labelSmall)
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = Color(0xFF67756C), modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Repair the wear.\nKeep what matters.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            PremiumPrimaryButton("Choose a photograph", onPick, Modifier.fillMaxWidth())
            if (onScan != null) {
                TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Scan a printed photo")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  Private by design · No subscription", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PremiumPickedBody(sourceUri: String, onRestore: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 12.dp)) {
        GalleryEyebrow("01 / Your photograph")
        Text("Let's bring it back.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 9.dp, bottom = 20.dp))
        PremiumPhotoMount(sourceUri, "Selected photograph", Modifier.fillMaxWidth().aspectRatio(.92f))
        Text("Scratches repaired. Faces refined.\nThe original stays yours, untouched.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 20.dp))
        PremiumPrimaryButton("Restore this photograph", onRestore, Modifier.fillMaxWidth())
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose another photo") }
    }
}

@Composable
internal fun PremiumPhotoMount(image: String, contentDescription: String, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(10.dp)) {
        AsyncImage(model = image, contentDescription = contentDescription,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun PremiumDoneBody(
    sourceUri: String, restoredUrl: String, identityWarning: Boolean, identityUnverified: Boolean,
    wasColorized: Boolean, cosineSimilarity: Double?, elapsedSeconds: Long?, stageResults: Map<Stage, StageResult>,
    onSave: () -> Unit, onShare: () -> Unit, onReport: () -> Unit, onReset: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 16.dp)) {
        GalleryEyebrow("03 / A new chapter")
        Text("Worth keeping.", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 8.dp, bottom = 5.dp))
        Text("Slide across to see what changed.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 18.dp))
        PhotoComparison(sourceUri, restoredUrl)
        IdentityTrustCard(identityWarning, identityUnverified)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PremiumPrimaryButton("Save photo", onSave, Modifier.weight(1f), Icons.Outlined.FileDownload)
            PremiumSecondaryButton("Share", onShare, Modifier.weight(.7f), Icons.Outlined.Share)
        }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore another photograph") }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
            Text("Restoration details", modifier = Modifier.weight(1f))
            Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (showDetails) {
            RestorationReport(cosineSimilarity, identityWarning, identityUnverified, wasColorized, elapsedSeconds, stageResults)
        }
        TextButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
            Text("Report a problem", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoComparison(original: String, restored: String) {
    var split by rememberSaveable(original, restored) { mutableFloatStateOf(.5f) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(.88f).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(original, restored) {
                detectDragGestures(onDragStart = { split = (it.x / size.width).coerceIn(0f, 1f) }) { change, _ ->
                    change.consume(); split = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }) {
            AsyncImage(restored, "Restored photograph", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            AsyncImage(original, "Original photograph", Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
            }, contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(Color.White, 17.dp.toPx(), Offset(x, size.height * .55f))
                drawLine(Color(0xFF17272B), Offset(x - 5.dp.toPx(), size.height * .55f - 5.dp.toPx()),
                    Offset(x - 5.dp.toPx(), size.height * .55f + 5.dp.toPx()), 1.5.dp.toPx())
                drawLine(Color(0xFF17272B), Offset(x + 5.dp.toPx(), size.height * .55f - 5.dp.toPx()),
                    Offset(x + 5.dp.toPx(), size.height * .55f + 5.dp.toPx()), 1.5.dp.toPx())
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PhotoLabel("ORIGINAL"); PhotoLabel("RESTORED")
            }
        }
        Slider(value = split, onValueChange = { split = it }, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "Compare original and restored photograph" })
    }
}

@Composable
private fun PhotoLabel(text: String) {
    Text(text, Modifier.background(Color(0xCC102024), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall, color = Color(0xFFF5F0E8))
}

@Composable
private fun IdentityTrustCard(identityWarning: Boolean, identityUnverified: Boolean) {
    val warning = identityWarning || identityUnverified
    Row(Modifier.fillMaxWidth().background(
        if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(if (warning) Icons.Outlined.Info else Icons.Outlined.CheckCircle, null, Modifier.size(20.dp),
            tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        Column(Modifier.padding(start = 10.dp)) {
            Text(when { identityWarning -> "Take a close look at the face"
                identityUnverified -> "The face check was unavailable"
                else -> "Face check passed" }, style = MaterialTheme.typography.titleSmall)
            Text(when { identityWarning -> "Some features may have changed. Compare with the original before saving."
                identityUnverified -> "Compare the face with your original before saving."
                else -> "The restored face is similar to the original. Give it your own final look." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
internal fun PremiumFailedBody(message: String, onRetry: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp, bottom = 24.dp)) {
        GalleryEyebrow("Restoration stopped")
        Icon(Icons.Outlined.ImageNotSupported, null, Modifier.padding(vertical = 30.dp).size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Let's try that\nonce more.", style = MaterialTheme.typography.displayMedium)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 28.dp))
        PremiumPrimaryButton("Try again", onRetry, Modifier.fillMaxWidth(), Icons.Outlined.Refresh)
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose another photo") }
    }
}

@Composable
internal fun PremiumPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Button(onClick, modifier.heightIn(min = 58.dp), shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (icon == null) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(21.dp))
    }
}

@Composable
internal fun PremiumSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(onClick, modifier.heightIn(min = 58.dp), shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
