package com.heirloom.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps Google Play Billing Library v7. Stub-grade for v1: the public API
 * is stable but the implementation is intentionally minimal — real launch
 * needs server-side receipt validation, which lives in a separate Worker
 * route once we wire Firestore.
 *
 * Lifecycle:
 *   - construct in Application/Activity scope
 *   - call connect() once
 *   - call refresh() to recompute entitlement from current purchases
 *   - call launchPurchase(activity, productId) to start a flow
 *   - observe entitlement via the StateFlow
 */
class BillingManager(context: Context, private val usage: UsageTracker) :
    PurchasesUpdatedListener {

    private val appContext = context.applicationContext

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private val _entitlement = MutableStateFlow<Entitlement>(
        Entitlement.FreeTier(usage.remaining())
    )
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private var armeniaExempt: Boolean = false

    val billingClient: BillingClient get() = client

    suspend fun connect(): Boolean = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            cont.resume(true); return@suspendCancellableCoroutine
        }
        client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {
                // Caller may retry connect(); we don't auto-retry here.
            }
        })
    }

    fun setArmeniaExempt(exempt: Boolean) {
        armeniaExempt = exempt
        if (exempt) _entitlement.value = Entitlement.ArmeniaExempt
        else recomputeFreeTier()
    }

    suspend fun refresh() {
        if (armeniaExempt) {
            _entitlement.value = Entitlement.ArmeniaExempt
            return
        }
        val active = activePurchases()
        when {
            active.any { it.products.contains(ProductIds.LIFETIME) } ->
                _entitlement.value = Entitlement.LifetimeUnlocked
            active.any { it.products.contains(ProductIds.YEARLY) } ->
                _entitlement.value = Entitlement.YearlySubscriber
            else -> recomputeFreeTier()
        }
        active.filter { !it.isAcknowledged }.forEach(::acknowledge)
    }

    /** Caller must invoke this only after a successful restoration. */
    fun consumeFreeRestoration() {
        if (armeniaExempt) return
        if (_entitlement.value is Entitlement.LifetimeUnlocked) return
        if (_entitlement.value is Entitlement.YearlySubscriber) return
        usage.increment()
        recomputeFreeTier()
    }

    suspend fun queryProductDetails(): List<ProductDetails> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ProductIds.LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ProductIds.YEARLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                )
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, list ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(list)
                } else {
                    cont.resume(emptyList())
                }
            }
        }
    }

    /** The one product the paywall sells. Null until Play has it (or offline). */
    suspend fun lifetimeDetails(): ProductDetails? =
        queryProductDetails().firstOrNull { it.productId == ProductIds.LIFETIME }

    fun launchPurchase(activity: Activity, details: ProductDetails) {
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (offerToken != null) productParamsBuilder.setOfferToken(offerToken)

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        val purchased = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        purchased.filter { !it.isAcknowledged }.forEach(::acknowledge)
        // Flip entitlement immediately so the paywall dismisses without a manual refresh.
        when {
            purchased.any { it.products.contains(ProductIds.LIFETIME) } ->
                _entitlement.value = Entitlement.LifetimeUnlocked
            purchased.any { it.products.contains(ProductIds.YEARLY) } ->
                _entitlement.value = Entitlement.YearlySubscriber
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { /* fire and forget; refresh() will reconcile */ }
    }

    private suspend fun activePurchases(): List<Purchase> {
        val inapp = queryPurchases(BillingClient.ProductType.INAPP)
        val subs = queryPurchases(BillingClient.ProductType.SUBS)
        return (inapp + subs).filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    private suspend fun queryPurchases(productType: String): List<Purchase> =
        suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(productType).build()
            ) { _, list -> cont.resume(list) }
        }

    private fun recomputeFreeTier() {
        val remaining = usage.remaining()
        _entitlement.value = if (remaining > 0) Entitlement.FreeTier(remaining)
        else Entitlement.PaywallRequired
    }
}
