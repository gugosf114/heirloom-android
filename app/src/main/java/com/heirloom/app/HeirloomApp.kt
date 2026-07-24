package com.heirloom.app

import android.app.Application
import com.heirloom.app.billing.BillingManager
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
        billing = BillingManager(this)
        appScope.launch {
            billing.start()
        }
    }
}
