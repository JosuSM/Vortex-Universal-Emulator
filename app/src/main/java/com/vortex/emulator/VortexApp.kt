package com.vortex.emulator

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VortexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VortexApp
            private set
    }
}
