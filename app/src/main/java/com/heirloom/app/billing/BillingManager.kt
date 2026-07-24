package com.heirloom.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.heirloom.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BillingManager(context: Context) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val clientId = ClientIdentity.get(appContext)
    private val attestor = PlayIntegrityAttestor(
        appContext,
        BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER,
    )
    private val server = BillingServerApi(clientId, attestor)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshMutex = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    private val _entitlement = MutableStateFlow<Entitlement>(Entitlement.Loading)
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    @Volatile
    private var sessionToken: String? = null

    suspend fun start() {
        attestor.prepare()
        refresh()
    }

    suspend fun refresh() {
        refreshMutex.withLock {
            _entitlement.value = Entitlement.Loading
            try {
                if (!connect()) {
                    throw IllegalStateException("Google Play is unavailable")
                }
                val receipts = activePurchases().map { purchase ->
                    PurchaseReceipt(
                        productId = purchase.products.single(),
                        purchaseToken = purchase.purchaseToken,
                    )
                }
                applyServerEntitlement(server.sync(receipts))
            } catch (error: Throwable) {
                sessionToken = null
                _entitlement.value = Entitlement.Unavailable(
                    when (error) {
                        is BillingServerException -> when (error.statusCode) {
                            403 -> "Install Heirloom from Google Play to restore photos."
                            else -> "Restoration access could not be verified. Try again."
                        }
                        else -> "Google Play could not verify restoration access. Try again."
                    },
                )
            }
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    fun restoreAuthorization(): RestoreAuthorization? =
        sessionToken?.let(::RestoreAuthorization)

    fun recordSuccessfulRestoration(serverRemaining: Int?) {
        val credits = _entitlement.value as? Entitlement.Credits ?: return
        val next = when {
            credits.freeRemaining > 0 -> credits.copy(freeRemaining = credits.freeRemaining - 1)
            credits.paidRemaining > 0 -> credits.copy(paidRemaining = credits.paidRemaining - 1)
            else -> credits
        }
        val reconciled = if (serverRemaining != null && serverRemaining != next.totalRemaining) {
            // A second device may have spent a paid credit. Refresh gets the exact split.
            refreshAsync()
            next
        } else {
            next
        }
        _entitlement.value =
            if (reconciled.totalRemaining > 0) reconciled else Entitlement.PaywallRequired
    }

    fun recordCreditsExhausted() {
        _entitlement.value = Entitlement.PaywallRequired
        refreshAsync()
    }

    suspend fun queryPackDetails(): List<ProductDetails> {
        if (!connect()) return emptyList()
        val products = ProductIds.PACKS.map { pack ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(pack.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                continuation.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryResult.productDetailsList.sortedBy { details ->
                            ProductIds.PACKS.indexOfFirst { it.productId == details.productId }
                        }
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    fun launchPurchase(activity: Activity, details: ProductDetails) {
        if (details.productId !in ProductIds.ALL) return
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                details.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull()
                    ?.offerToken
                    ?.let(::setOfferToken)
            }
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(clientId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refreshAsync()
        }
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        if (
            result.responseCode == BillingClient.BillingResponseCode.OK &&
            purchases?.any {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    it.products.any(ProductIds.ALL::contains)
            } == true
        ) {
            refreshAsync()
        }
    }

    private suspend fun connect(): Boolean = suspendCancellableCoroutine { continuation ->
        if (client.isReady) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }
        client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                continuation.resume(
                    result.responseCode == BillingClient.BillingResponseCode.OK,
                )
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private suspend fun activePurchases(): List<Purchase> =
        suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            ) { result, purchases ->
                continuation.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        purchases.filter {
                            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                it.products.size == 1 &&
                                it.products.single() in ProductIds.ALL
                        }
                    } else {
                        emptyList()
                    },
                )
            }
        }

    private fun applyServerEntitlement(serverEntitlement: ServerEntitlement) {
        sessionToken = serverEntitlement.sessionToken
        _entitlement.value =
            if (serverEntitlement.totalRemaining > 0) {
                Entitlement.Credits(
                    freeRemaining = serverEntitlement.freeRemaining,
                    paidRemaining = serverEntitlement.paidRemaining,
                )
            } else {
                Entitlement.PaywallRequired
            }
    }
}
