package com.heirloom.app

import android.app.Application
import com.heirloom.app.billing.BillingManager
import com.heirloom.app.billing.GeoExemption
import com.heirloom.app.billing.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HeirloomApp : Application() {

    lateinit var billing: BillingManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        billing = BillingManager(this, UsageTracker(this))
        appScope.launch {
            val connected = billing.connect()
            if (connected) {
                billing.setArmeniaExempt(
                    GeoExemption.isArmeniaExempt(this@HeirloomApp, billing.billingClient)
                )
            } else {
                // Play unavailable (no GMS, offline): geo check without billing source.
                billing.setArmeniaExempt(GeoExemption.isArmeniaExempt(this@HeirloomApp, null))
            }
            billing.refresh()
        }
    }
}
