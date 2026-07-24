package com.heirloom.app.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTest {
    @Test
    fun onlyPositiveFreeTierCanRestore() {
        assertTrue(Entitlement.FreeTier(1).allowsRestore())
        assertFalse(Entitlement.FreeTier(0).allowsRestore())
        assertFalse(Entitlement.PaywallRequired.allowsRestore())
    }

    @Test
    fun exemptAndPaidUsersCanRestore() {
        assertTrue(Entitlement.ArmeniaExempt.allowsRestore())
        assertTrue(Entitlement.LifetimeUnlocked.allowsRestore())
    }
}
