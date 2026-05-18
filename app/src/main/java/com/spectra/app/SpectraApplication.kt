package com.spectra.app

import android.app.Application
import com.spectra.camera.ToneCurveEngine
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SpectraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ToneCurveEngine.loadLuts(assets)
    }
}
