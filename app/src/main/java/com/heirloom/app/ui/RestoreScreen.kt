package com.heirloom.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.android.billingclient.api.ProductDetails
import com.heirloom.app.HeirloomApp
import com.heirloom.app.R
import com.heirloom.app.billing.Entitlement
import com.heirloom.app.billing.allowsRestore
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.heirloom.app.data.RestoreState
import com.heirloom.app.data.RestoreViewModel
import com.heirloom.app.data.Stage
import com.heirloom.app.data.StageResult
import com.heirloom.app.ui.theater.RestorationTheater
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

@Composable
fun RestoreScreen(viewModel: RestoreViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val reportThanksMessage = stringResource(R.string.report_thanks)
    val reportFailedMessage = stringResource(R.string.report_failed)

    val billing = (context.applicationContext as HeirloomApp).billing
    val entitlement by billing.entitlement.collectAsStateWithLifecycle()
    var showPaywall by remember { mutableStateOf(false) }
    var lifetimeDetails by remember { mutableStateOf<ProductDetails?>(null) }
    var reportTarget by remember { mutableStateOf<RestoreState.Done?>(null) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var pendingLegacySave by remember { mutableStateOf<String?>(null) }

    val saveRestoredPhoto: (String) -> Unit = { restoredUrl ->
        scope.launch {
            runCatching { PhotoIo.saveToGallery(context, restoredUrl) }
                .onSuccess { snackbarHostState.showSnackbar("Saved to Pictures/Heirloom") }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Save failed") }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val restoredUrl = pendingLegacySave
        pendingLegacySave = null
        if (granted && restoredUrl != null) {
            saveRestoredPhoto(restoredUrl)
        } else if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("Storage permission is needed to save this photo.")
            }
        }
    }

    // Load the product lazily the first time the paywall opens.
    LaunchedEffect(showPaywall) {
        if (showPaywall && lifetimeDetails == null) {
            lifetimeDetails = billing.lifetimeDetails()
        }
    }
    // A completed purchase (or geo exemption) closes the paywall by itself.
    LaunchedEffect(entitlement) {
        if (showPaywall && entitlement !is Entitlement.PaywallRequired) showPaywall = false
    }
    // One free restoration is consumed only when a restore actually succeeds —
    // exactly once per result. Keyed on the restored URL (unique per restore) and
    // saved across config changes so a rotation/re-entry while Done can't double-count.
    var lastConsumedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        val s = state
        if (s is RestoreState.Done && s.restoredUrl != lastConsumedUrl) {
            lastConsumedUrl = s.restoredUrl
            billing.consumeFreeRestoration()
        }
    }

    // 1. Configure the ML Kit Document Scanner
    val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(1)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
        .build()
        
    val scanner = GmsDocumentScanning.getClient(scannerOptions)

    // 2. Register the launcher to handle the scanner result
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.firstOrNull()?.imageUri?.let { uri ->
                viewModel.photoCropped(uri)
            }
        }
    }

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

            (entitlement as? Entitlement.FreeTier)?.let { free ->
                Text(
                    text = stringResource(R.string.free_tier_remaining, free.remaining).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                            activity?.let {
                                scanner.getStartScanIntent(it)
                                    .addOnSuccessListener { intentSender ->
                                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    }
                                    .addOnFailureListener { e ->
                                        scope.launch { snackbarHostState.showSnackbar("Scanner failed: ${e.message}") }
                                    }
                            }
                        }
                    )
                    is RestoreState.Picked -> PickedBody(
                        sourceUri = current.source.toString(),
                        onRestore = {
                            if (entitlement.allowsRestore()) viewModel.startRestoration()
                            else showPaywall = true
                        },
                        onReset = viewModel::reset,
                    )
                    is RestoreState.Processing -> RestorationTheater(
                        sourceUri = current.source.toString(),
                        stage = current.stage,
                        stageResults = current.stageResults,
                    )
                    is RestoreState.Done -> DoneBody(
                        sourceUri = current.source.toString(),
                        restoredUrl = current.restoredUrl,
                        identityWarning = current.identityWarning,
                        identityUnverified = current.identityUnverified,
                        wasColorized = current.wasColorized,
                        cosineSimilarity = current.cosineSimilarity,
                        elapsedSeconds = current.elapsedSeconds,
                        stageResults = current.stageResults,
                        onSave = {
                            val needsLegacyPermission =
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ) != PackageManager.PERMISSION_GRANTED
                            if (needsLegacyPermission) {
                                pendingLegacySave = current.restoredUrl
                                storagePermissionLauncher.launch(
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            } else {
                                saveRestoredPhoto(current.restoredUrl)
                            }
                        },
                        onShare = {
                            scope.launch {
                                runCatching { PhotoIo.share(context, current.restoredUrl) }
                                    .onFailure { snackbarHostState.showSnackbar(it.message ?: "Share failed") }
                            }
                        },
                        onReport = { reportTarget = current },
                        onReset = viewModel::reset,
                    )
                    is RestoreState.Failed -> FailedBody(
                        message = current.message,
                        onRetry = {
                            // Retry must respect the same paywall gate as the initial
                            // restore — a failure mid-session shouldn't be a free bypass.
                            if (entitlement.allowsRestore()) viewModel.startRestoration()
                            else showPaywall = true
                        },
                        onReset = viewModel::reset,
                    )
                }
            }
        }
    }

    if (showPaywall) {
        PaywallDialog(
            details = lifetimeDetails,
            onBuy = { d -> activity?.let { billing.launchPurchase(it, d) } },
            onDismiss = { showPaywall = false },
        )
    }

    reportTarget?.let { target ->
        ReportResultDialog(
            submitting = reportSubmitting,
            onSubmit = { reason, details ->
                scope.launch {
                    reportSubmitting = true
                    viewModel.reportResult(target, reason, details)
                        .onSuccess {
                            reportTarget = null
                            snackbarHostState.showSnackbar(reportThanksMessage)
                        }
                        .onFailure {
                            snackbarHostState.showSnackbar(reportFailedMessage)
                        }
                    reportSubmitting = false
                }
            },
            onDismiss = {
                if (!reportSubmitting) reportTarget = null
            },
        )
    }
}

private data class ReportReason(val key: String, val labelRes: Int)

private val reportReasons = listOf(
    ReportReason("wrong_person", R.string.report_reason_wrong_person),
    ReportReason("distorted_face", R.string.report_reason_distorted_face),
    ReportReason("offensive_or_unexpected", R.string.report_reason_unexpected),
    ReportReason("poor_quality", R.string.report_reason_poor_quality),
    ReportReason("other", R.string.report_reason_other),
)

@Composable
private fun ReportResultDialog(
    submitting: Boolean,
    onSubmit: (reason: String, details: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedReason by rememberSaveable { mutableStateOf(reportReasons.first().key) }
    var details by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.report_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                reportReasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedReason == reason.key,
                            onClick = { selectedReason = reason.key },
                            enabled = !submitting,
                        )
                        Text(
                            text = stringResource(reason.labelRes),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(1_000) },
                    label = { Text(stringResource(R.string.report_details)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(selectedReason, details.trim()) },
                enabled = !submitting,
            ) {
                Text(
                    if (submitting) {
                        stringResource(R.string.report_sending)
                    } else {
                        stringResource(R.string.report_submit)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.report_cancel))
            }
        },
    )
}

@Composable
private fun PaywallDialog(
    details: ProductDetails?,
    onBuy: (ProductDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paywall_title)) },
        text = {
            Column {
                Text(stringResource(R.string.paywall_body))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.paywall_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (details != null && price != null) {
                Button(onClick = { onBuy(details) }) {
                    Text(stringResource(R.string.paywall_cta, price))
                }
            } else {
                Text(
                    stringResource(R.string.paywall_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.paywall_not_now)) }
        },
    )
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
            text = stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tagline).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                label = stringResource(R.string.pick_photo).uppercase(),
                onClick = onPick,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "SYSTEM STANDBY.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCanvas(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "NO INPUT DATA",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                label = stringResource(R.string.restore).uppercase(),
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onReset, shape = RectangleShape) {
                Text(stringResource(R.string.start_over).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DoneBody(
    sourceUri: String,
    restoredUrl: String,
    identityWarning: Boolean,
    identityUnverified: Boolean,
    wasColorized: Boolean,
    cosineSimilarity: Double?,
    elapsedSeconds: Long?,
    stageResults: Map<Stage, StageResult>,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReport: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (identityWarning) {
            IdentityWarningBanner()
            Spacer(Modifier.height(8.dp))
        } else if (identityUnverified) {
            Text(
                text = stringResource(R.string.identity_unverified).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BeforeAfterTile(
                label = "INPUT",
                image = sourceUri,
                modifier = Modifier.weight(1f),
            )
            BeforeAfterTile(
                label = if (wasColorized) "OUTPUT (+COLOR)" else "OUTPUT",
                image = restoredUrl,
                modifier = Modifier.weight(1f),
                accent = true,
            )
        }

        Spacer(Modifier.height(12.dp))

        RestorationReport(
            cosineSimilarity = cosineSimilarity,
            identityWarning = identityWarning,
            identityUnverified = identityUnverified,
            wasColorized = wasColorized,
            elapsedSeconds = elapsedSeconds,
            stageResults = stageResults,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                label = stringResource(R.string.save).uppercase(),
                onClick = onSave,
                icon = Icons.Outlined.Save,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = stringResource(R.string.share).uppercase(),
                onClick = onShare,
                icon = Icons.Outlined.Share,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReport, shape = RectangleShape) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.report_problem).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onReset, shape = RectangleShape) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.start_over).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        AsyncImage(
            model = image,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(
                    width = 1.dp,
                    color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RectangleShape,
                ),
        )
    }
}

@Composable
private fun IdentityWarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.error, RectangleShape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "[WARN] IDENTITY DRIFT DETECTED.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
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
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(label = "RETRY", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReset, shape = RectangleShape) {
            Text(stringResource(R.string.start_over).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), 
            contentColor = MaterialTheme.colorScheme.primary
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier.height(56.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        } ?: run {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold)
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
        shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(56.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
