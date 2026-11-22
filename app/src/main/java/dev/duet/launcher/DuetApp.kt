package dev.duet.launcher

import android.app.Application

class DuetApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        apps = AppRepository(this)
    }

    companion object {
        lateinit var instance: DuetApp
            private set
    }
}
