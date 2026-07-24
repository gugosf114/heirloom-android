package com.heirloom.app.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTest {
    @Test
    fun onlyPositiveServerCreditsCanRestore() {
        assertTrue(Entitlement.Credits(freeRemaining = 1, paidRemaining = 0).allowsRestore())
        assertTrue(Entitlement.Credits(freeRemaining = 0, paidRemaining = 5).allowsRestore())
        assertFalse(Entitlement.Credits(freeRemaining = 0, paidRemaining = 0).allowsRestore())
        assertFalse(Entitlement.PaywallRequired.allowsRestore())
        assertFalse(Entitlement.Loading.allowsRestore())
    }

    @Test
    fun packCatalogMatchesLaunchPricing() {
        assertTrue(ProductIds.ALL.contains(ProductIds.FIVE))
        assertTrue(ProductIds.ALL.contains(ProductIds.TWENTY))
        assertTrue(ProductIds.ALL.contains(ProductIds.FIFTY))
        assertTrue(ProductIds.PACKS.map { it.restorations } == listOf(5, 20, 50))
    }
}
