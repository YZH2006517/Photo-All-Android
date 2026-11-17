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
        resetQuirksAfterSystemUpdate()
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

    /**
     * What this phone needed (`camera_quirks`) was learnt on one system build; an update may
     * have fixed the camera driver, so after one it is all tried again: cleared here, before
     * any screen reads it, and said on the camera screen (CameraActivity). The first start
     * only notes the build (nothing to compare with). The GPU's own memory is keyed by the
     * build already. An import in progress (`import_raw_inflight`) is kept: it still says the
     * last one died.
     */
    private fun resetQuirksAfterSystemUpdate() {
        val q = getSharedPreferences("camera_quirks", MODE_PRIVATE)
        val build = android.os.Build.FINGERPRINT
        val seen = q.getString("system_build", null)
        if (seen == build) return
        val keep = setOf("system_build", "stall_reset_v39", "import_raw_inflight", "quirks_reset_notice")
        val learnt = q.all.keys.filter { it !in keep }
        q.edit().apply {
            if (seen != null && learnt.isNotEmpty()) {
                learnt.forEach { remove(it) }
                putBoolean("quirks_reset_notice", true)
            }
            putString("system_build", build)
        }.commit()
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
