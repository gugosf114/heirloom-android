package com.heirloom.app

import android.app.Application

class HeirloomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: Firebase init lands here once google-services.json is in place.
    }
}
