package com.heirloom.app.billing

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Armenia free-tier check.
 *
 * Sources we consult, most-authoritative first:
 *   1. Play Store account country (BillingClient.getBillingConfig)
 *      — what Google says is the user's billing region.
 *   2. SIM/network country (TelephonyManager)
 *      — physical location signal, harder to spoof.
 *   3. Device locale (Locale.getDefault())
 *      — easiest to fake but useful as a tie-breaker.
 *
 * If ANY source says "AM", the user is exempt. We're optimistic on purpose:
 * a user with an Armenian SIM and a US Play account (diaspora) should still
 * get the free tier. The cost of being wrong here is one free unlock — fine.
 */
object GeoExemption {

    suspend fun isArmeniaExempt(context: Context, billing: BillingClient?): Boolean {
        // Device locale is intentionally NOT a sufficient signal on its own — it's
        // a one-tap Settings change, so locale-only would let anyone opt into the
        // free tier. Keep the harder-to-spoof signals (SIM / network / Play billing
        // country); a real Armenian SIM or Play account still qualifies, so the
        // diaspora-friendly intent survives.
        if (simCountry(context) == "AM") return true
        if (networkCountry(context) == "AM") return true
        if (billing != null && billingCountry(billing) == "AM") return true
        return false
    }

    private fun simCountry(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""
        return tm.simCountryIso?.uppercase(Locale.ROOT).orEmpty()
    }

    private fun networkCountry(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""
        return tm.networkCountryIso?.uppercase(Locale.ROOT).orEmpty()
    }

    private suspend fun billingCountry(billing: BillingClient): String =
        suspendCancellableCoroutine { cont ->
            billing.getBillingConfigAsync(
                GetBillingConfigParams.newBuilder().build(),
            ) { result: BillingResult, config ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && config != null) {
                    cont.resume(config.countryCode.uppercase(Locale.ROOT))
                } else {
                    cont.resume("")
                }
            }
        }

    @Suppress("unused")
    fun apiLevelOk(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
