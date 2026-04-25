package com.heirloom.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.heirloom.app.R
import com.heirloom.app.data.RestoreState
import com.heirloom.app.data.RestoreViewModel
import com.heirloom.app.data.Stage
import com.heirloom.app.ui.theme.Brass
import com.heirloom.app.ui.theme.CreamDeep
import com.heirloom.app.ui.theme.Ink
import com.heirloom.app.ui.theme.InkSoft
import com.heirloom.app.ui.theme.Oxblood
import kotlinx.coroutines.launch

@Composable
fun RestoreScreen(viewModel: RestoreViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.pickPhoto(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeirloomHeader()

            AnimatedContent(
                targetState = state,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "restore-state",
                contentKey = { it::class },
            ) { current ->
                when (current) {
                    is RestoreState.Idle -> IdleBody(
                        onPick = {
                            pickPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    is RestoreState.Picked -> PickedBody(
                        sourceUri = current.source.toString(),
                        onRestore = viewModel::startRestoration,
                        onReset = viewModel::reset,
                    )
                    is RestoreState.Processing -> ProcessingBody(
                        sourceUri = current.source.toString(),
                        stage = current.stage,
                    )
                    is RestoreState.Done -> DoneBody(
                        sourceUri = current.source.toString(),
                        restoredUrl = current.restoredUrl,
                        identityWarning = current.identityWarning,
                        wasColorized = current.wasColorized,
                        onSave = {
                            scope.launch {
                                runCatching { PhotoIo.saveToGallery(context, current.restoredUrl) }
                                    .onSuccess { snackbarHostState.showSnackbar("Saved to Pictures/Heirloom") }
                                    .onFailure { snackbarHostState.showSnackbar(it.message ?: "Save failed") }
                            }
                        },
                        onShare = {
                            scope.launch {
                                runCatching { PhotoIo.share(context, current.restoredUrl) }
                                    .onFailure { snackbarHostState.showSnackbar(it.message ?: "Share failed") }
                            }
                        },
                        onReset = viewModel::reset,
                    )
                    is RestoreState.Failed -> FailedBody(
                        message = current.message,
                        onRetry = viewModel::startRestoration,
                        onReset = viewModel::reset,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeirloomHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = Ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdleBody(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        EmptyCanvas(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(vertical = 8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                label = stringResource(R.string.pick_photo),
                onClick = onPick,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Old. Damaged. Faded. Bring it back.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
            )
        }
    }
}

@Composable
private fun EmptyCanvas(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(CreamDeep)
            .border(1.dp, InkSoft.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = InkSoft.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No photo yet",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PickedBody(sourceUri: String, onRestore: () -> Unit, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        AsyncImage(
            model = sourceUri,
            contentDescription = "Selected photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, InkSoft.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                label = stringResource(R.string.restore),
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.start_over), color = InkSoft)
            }
        }
    }
}

@Composable
private fun ProcessingBody(sourceUri: String, stage: Stage) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = sourceUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.Black.copy(alpha = 0.85f)),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(24.dp),
        ) {
            CircularProgressIndicator(color = Brass, strokeWidth = 3.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = stageLabel(stage),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFF4ECDD),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun stageLabel(stage: Stage): String = when (stage) {
    Stage.Uploading -> stringResource(R.string.status_uploading)
    Stage.RepairingDamage -> stringResource(R.string.status_repairing)
    Stage.RestoringFaces -> stringResource(R.string.status_faces)
    Stage.Upscaling -> stringResource(R.string.status_upscaling)
    Stage.CheckingIdentity -> stringResource(R.string.status_checking)
    Stage.Colorizing -> stringResource(R.string.status_colorizing)
    Stage.Finalizing -> stringResource(R.string.status_finalizing)
}

@Composable
private fun DoneBody(
    sourceUri: String,
    restoredUrl: String,
    identityWarning: Boolean,
    wasColorized: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (identityWarning) {
            IdentityWarningBanner()
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BeforeAfterTile(
                label = "Before",
                image = sourceUri,
                modifier = Modifier.weight(1f),
            )
            BeforeAfterTile(
                label = if (wasColorized) "Restored · Colorized" else "Restored",
                image = restoredUrl,
                modifier = Modifier.weight(1f),
                accent = true,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                label = stringResource(R.string.save),
                onClick = onSave,
                icon = Icons.Outlined.Save,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = stringResource(R.string.share),
                onClick = onShare,
                icon = Icons.Outlined.Share,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReset) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = InkSoft)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.start_over), color = InkSoft)
        }
    }
}

@Composable
private fun BeforeAfterTile(
    label: String,
    image: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (accent) Brass else InkSoft,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        AsyncImage(
            model = image,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(2.dp))
                .border(
                    width = if (accent) 2.dp else 1.dp,
                    color = if (accent) Brass else InkSoft.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Composable
private fun IdentityWarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Oxblood.copy(alpha = 0.12f))
            .border(1.dp, Oxblood, RoundedCornerShape(2.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Oxblood,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.warning_identity_drift),
            style = MaterialTheme.typography.bodyMedium,
            color = Oxblood,
        )
    }
}

@Composable
private fun FailedBody(message: String, onRetry: () -> Unit, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Oxblood,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(label = "Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.start_over), color = InkSoft)
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color(0xFFF4ECDD)),
        modifier = modifier.height(56.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        } ?: run {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        border = BorderStroke(1.dp, Ink),
        modifier = modifier.height(56.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
