package com.heirloom.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManagerFactory

/** Share the app, open its Play page, ask for a review. Always the production package, never the .preview suffix. */
internal object AppLinks {
    const val PLAY_PACKAGE = "com.wimlabs.heirloom"
    const val STORE_URL = "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE"

    fun shareApp(context: Context) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Heirloom")
            putExtra(Intent.EXTRA_TEXT, "Heirloom brings old family photos back. Private, no subscription.\n$STORE_URL")
        }
        context.startActivity(Intent.createChooser(send, "Share Heirloom").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openStorePage(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Google's in-app review sheet. Play decides whether to show it; when it will not, the Play page opens instead. */
    fun requestReview(activity: Activity) {
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (request.isSuccessful) manager.launchReviewFlow(activity, request.result)
            else openStorePage(activity)
        }
    }
}
