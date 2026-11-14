package org.photoall.film

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Only to catch crashes from the first moment, in every screen. */
class PhotoAllApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the GPU probe's process (engine/GpuProbe): only there to compile, nothing to set up
        if (getProcessName().endsWith(org.photoall.film.engine.GpuProbe.PROCESS_SUFFIX)) return
        CrashReport.install(this)
        org.photoall.film.engine.GpuProbe.app = this
        // no status bar in any screen of the app: it comes back only on a swipe from the top
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = hideStatusBar(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun hideStatusBar(activity: Activity) {
        val window = activity.window ?: return
        window.decorView.post {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
