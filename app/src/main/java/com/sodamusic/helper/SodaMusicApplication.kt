package com.sodamusic.helper

import android.app.Application
import com.sodamusic.helper.automation.AutomationRuntime
import com.ven.assists.AssistsCore

class SodaMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AssistsCore.init(this)
        AutomationRuntime.initialize(this)
    }
}
