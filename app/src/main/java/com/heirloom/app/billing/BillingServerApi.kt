package com.heirloom.app.billing

import com.heirloom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PurchaseReceipt(
    val productId: String,
    val purchaseToken: String,
)

data class ServerEntitlement(
    val sessionToken: String,
    val freeRemaining: Int,
    val paidRemaining: Int,
) {
    val totalRemaining: Int get() = freeRemaining + paidRemaining
}

data class RestoreAuthorization(
    val sessionToken: String,
    val requestId: String = UUID.randomUUID().toString(),
)

class BillingServerException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class BillingServerApi(
    private val clientId: String,
    private val attestor: PlayIntegrityAttestor,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sync(purchases: List<PurchaseReceipt>): ServerEntitlement =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val purchaseBinding = canonicalPurchaseBinding(purchases)
            val integrityToken = attestor.token(
                SYNC_PATH,
                clientId,
                requestId,
                purchaseBinding,
            )
            val purchaseJson = JSONArray().apply {
                purchases.forEach { purchase ->
                    put(
                        JSONObject()
                            .put("product_id", purchase.productId)
                            .put("purchase_token", purchase.purchaseToken),
                    )
                }
            }
            val body = JSONObject()
                .put("client_id", clientId)
                .put("request_id", requestId)
                .put("integrity_token", integrityToken)
                .put("purchases", purchaseJson)
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("${BuildConfig.WORKER_BASE_URL}$SYNC_PATH")
                .apply {
                    if (BuildConfig.APP_SHARED_SECRET.isNotEmpty()) {
                        header("X-App-Key", BuildConfig.APP_SHARED_SECRET)
                    }
                }
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching {
                        JSONObject(responseText).optString("error")
                    }.getOrNull().orEmpty()
                    throw BillingServerException(
                        response.code,
                        error.ifBlank { "Could not verify restoration access" },
                    )
                }
                val json = JSONObject(responseText)
                ServerEntitlement(
                    sessionToken = json.getString("session_token"),
                    freeRemaining = json.getInt("free_remaining"),
                    paidRemaining = json.getInt("paid_remaining"),
                )
            }
        }

    companion object {
        private const val SYNC_PATH = "/billing/sync"
        private val JSON = "application/json".toMediaType()

        internal fun canonicalPurchaseBinding(purchases: List<PurchaseReceipt>): String =
            purchases
                .sortedWith(compareBy(PurchaseReceipt::productId, PurchaseReceipt::purchaseToken))
                .joinToString("\n") { purchase ->
                    "${purchase.productId.length}:${purchase.productId}" +
                        "${purchase.purchaseToken.length}:${purchase.purchaseToken}"
                }
    }
}
