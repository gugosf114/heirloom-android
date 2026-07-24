package com.heirloom.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BillingServerApiTest {
    @Test
    fun purchaseBindingIsOrderIndependentAndReceiptBound() {
        val first = PurchaseReceipt(
            productId = ProductIds.FIVE,
            purchaseToken = "token-one",
        )
        val second = PurchaseReceipt(
            productId = ProductIds.TWENTY,
            purchaseToken = "token-two",
        )

        val forward = BillingServerApi.canonicalPurchaseBinding(listOf(first, second))
        val reversed = BillingServerApi.canonicalPurchaseBinding(listOf(second, first))
        val tampered = BillingServerApi.canonicalPurchaseBinding(
            listOf(first.copy(purchaseToken = "tampered-token"), second),
        )

        assertEquals(forward, reversed)
        assertNotEquals(forward, tampered)
    }
}
