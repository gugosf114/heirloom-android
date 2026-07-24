package com.heirloom.app.data

import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RestoreHttpClientTest {
    @Test
    fun restorationUsesStableNonReplayingTransport() {
        val client = buildRestoreHttpClient()

        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(620_000, client.readTimeoutMillis)
        assertEquals(90_000, client.writeTimeoutMillis)
    }
}
