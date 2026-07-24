package com.heirloom.app.billing

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class PlayIntegrityAttestor(
    context: Context,
    private val cloudProjectNumber: Long,
) {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val providerMutex = Mutex()

    @Volatile
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun prepare(): Boolean {
        if (cloudProjectNumber <= 0L) return false
        return runCatching {
            preparedProvider()
            true
        }.getOrDefault(false)
    }

    suspend fun token(
        path: String,
        clientId: String,
        requestId: String,
        purchaseBinding: String,
    ): String {
        if (cloudProjectNumber <= 0L) return ""
        val requestHash = requestHash(path, clientId, requestId, purchaseBinding)
        val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()
        return runCatching {
            preparedProvider().request(request).await().token()
        }.recoverCatching {
            providerMutex.withLock { provider = null }
            preparedProvider().request(request).await().token()
        }.getOrThrow()
    }

    private suspend fun preparedProvider(): StandardIntegrityManager.StandardIntegrityTokenProvider {
        provider?.let { return it }
        return providerMutex.withLock {
            provider ?: manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build(),
            ).await().also { provider = it }
        }
    }

    companion object {
        internal fun requestHash(
            path: String,
            clientId: String,
            requestId: String,
            purchaseBinding: String,
        ): String {
            val canonical = "POST\n$path\n$clientId\n$requestId\n$purchaseBinding"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(
                digest,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }
    }
}
