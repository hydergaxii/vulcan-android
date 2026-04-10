package com.vulcan.app

import android.app.Application
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VulcanApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize logger first — everything logs
        VulcanLogger.init(this)
        VulcanLogger.i("🔥 Vulcan v2.0.0 — The Forge ignites")

        // Initialize storage directories
        StorageManager.initialize(this)

        VulcanLogger.i("Vulcan initialized — storage root: ${StorageManager.getRoot(this).path}")
    }
}
