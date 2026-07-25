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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.billingclient.api.ProductDetails
import com.heirloom.app.HeirloomApp
import com.heirloom.app.R
import com.heirloom.app.billing.Entitlement
import com.heirloom.app.billing.ProductIds
import com.heirloom.app.billing.RestorationPack
import com.heirloom.app.billing.allowsRestore
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.heirloom.app.data.RestoreState
import com.heirloom.app.data.RestoreViewModel
import com.heirloom.app.ui.theater.RestorationTheater
import kotlinx.coroutines.launch
import android.app.Activity
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
    var packDetails by remember { mutableStateOf<List<ProductDetails>>(emptyList()) }
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

    // Load localized Google Play prices lazily when the pack chooser opens.
    LaunchedEffect(showPaywall) {
        if (showPaywall && packDetails.isEmpty()) {
            packDetails = billing.queryPackDetails()
        }
    }
    // A server-verified purchase closes the pack chooser by itself.
    LaunchedEffect(entitlement) {
        val credits = entitlement as? Entitlement.Credits
        if (showPaywall && credits != null && credits.totalRemaining > 0) {
            showPaywall = false
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeirloomBrandHeader(
                    creditsRemaining =
                        (entitlement as? Entitlement.Credits)?.totalRemaining,
                )

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
                        is RestoreState.Idle -> PremiumIdleBody(
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
                            },
                        )
                        is RestoreState.Picked -> PremiumPickedBody(
                            sourceUri = current.source.toString(),
                            onRestore = {
                                when (val access = entitlement) {
                                    is Entitlement.Credits -> {
                                        if (access.allowsRestore()) viewModel.startRestoration()
                                        else showPaywall = true
                                    }
                                    Entitlement.PaywallRequired -> showPaywall = true
                                    Entitlement.Loading -> scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Checking restoration access…",
                                        )
                                    }
                                    is Entitlement.Unavailable -> scope.launch {
                                        snackbarHostState.showSnackbar(access.message)
                                        billing.refreshAsync()
                                    }
                                }
                            },
                            onReset = viewModel::reset,
                        )
                        is RestoreState.Processing -> RestorationTheater(
                            sourceUri = current.source.toString(),
                            stage = current.stage,
                            stageResults = current.stageResults,
                        )
                        is RestoreState.Done -> PremiumDoneBody(
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
                        is RestoreState.Failed -> PremiumFailedBody(
                            message = current.message,
                            onRetry = {
                                when (val access = entitlement) {
                                    is Entitlement.Credits -> {
                                        if (access.allowsRestore()) viewModel.startRestoration()
                                        else showPaywall = true
                                    }
                                    Entitlement.PaywallRequired -> showPaywall = true
                                    Entitlement.Loading -> billing.refreshAsync()
                                    is Entitlement.Unavailable -> {
                                        billing.refreshAsync()
                                        scope.launch {
                                            snackbarHostState.showSnackbar(access.message)
                                        }
                                    }
                                }
                            },
                            onReset = viewModel::reset,
                        )
                    }
                }
            }
        }
    }

    if (showPaywall) {
        PaywallDialog(
            details = packDetails,
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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                text = stringResource(R.string.report_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
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
                    shape = RoundedCornerShape(14.dp),
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
    details: List<ProductDetails>,
    onBuy: (ProductDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.paywall_body))
                Spacer(Modifier.height(14.dp))
                ProductIds.PACKS.forEach { pack ->
                    val product = details.firstOrNull { it.productId == pack.productId }
                    PackPurchaseButton(
                        pack = pack,
                        product = product,
                        onBuy = onBuy,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    stringResource(R.string.paywall_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.paywall_not_now)) }
        },
    )
}

@Composable
private fun PackPurchaseButton(
    pack: RestorationPack,
    product: ProductDetails?,
    onBuy: (ProductDetails) -> Unit,
) {
    val playPrice = product?.oneTimePurchaseOfferDetailsList
        ?.firstOrNull()
        ?.formattedPrice
        ?: product?.oneTimePurchaseOfferDetails?.formattedPrice
        ?: pack.expectedUsdPrice
    OutlinedButton(
        onClick = { product?.let(onBuy) },
        enabled = product != null,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.pack_restorations, pack.restorations),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pack.restorations == 20) {
                Text(
                    text = stringResource(R.string.pack_most_popular),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = if (product != null) playPrice else stringResource(R.string.pack_loading),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
