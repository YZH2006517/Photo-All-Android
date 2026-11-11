package org.photoall.film

import android.app.Application

/** Only to catch crashes from the first moment, in every screen. */
class PhotoAllApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}
