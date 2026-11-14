package org.photoall.film.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Whether this phone's GPU driver can compile the HDR+ merge kernels (hp_*), found out in
 * a process of its own ([Probe], ":gpuprobe") before the app's engine ever tries: some
 * Mali drivers (MediaTek, Android 17) crash compiling hp_align, and in the app's process
 * that crash ends the app on its first start. A probe that dies, hangs or fails seals the
 * merge away: bursts then develop their reference frame alone. The answer is kept per app
 * version and system build, so an update of either asks again.
 */
object GpuProbe {
    private const val PREFS = "gpu_probe"
    private const val CONNECT_SECONDS = 10L
    private const val COMPILE_SECONDS = 45L
    private const val CODE_PROBE = IBinder.FIRST_CALL_TRANSACTION

    /** The app (main process only), set by PhotoAllApp. */
    @Volatile var app: Context? = null

    private val lock = Any()

    /** What the probe found on this version and build, or null if it has not run. */
    fun verdict(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(context), null)

    /** True when the merge kernels may be compiled here; probes first if not yet known. Blocks. */
    fun burstAllowed(): Boolean {
        val context = app ?: return true // not the app's own process: decide nothing
        synchronized(lock) {
            verdict(context)?.let { return it == "ok" }
            val result = probe(context)
            android.util.Log.i("GpuProbe", "verdict: $result")
            // the probe never ran (a system that holds services back): no answer, ask next time,
            // and keep the merge, as nothing says the driver can't take it
            if (result.startsWith("unknown")) return true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .clear().putString(key(context), result).commit()
            return result == "ok"
        }
    }

    /** "ok", or "sealed: why". */
    private fun probe(context: Context): String {
        val stageFile = stageFile(context)
        stageFile.delete()
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onBindingDied(name: ComponentName?) { connected.countDown() }
            override fun onNullBinding(name: ComponentName?) { connected.countDown() }
        }
        val callbacks = Executors.newSingleThreadExecutor()
        val intent = Intent(context, Probe::class.java)
        try {
            // Some systems (Samsung's process freezer) answer false here and start the service
            // a moment later all the same: wait for the connection either way.
            val bound = context.bindService(intent, Context.BIND_AUTO_CREATE, callbacks, connection)
            if (!connected.await(CONNECT_SECONDS, TimeUnit.SECONDS) || binder == null)
                return "unknown: 检测进程没有启动（bindService=$bound）"
            // the call on a thread of its own, so a driver that hangs can be given up on
            var answer: String? = null
            val done = CountDownLatch(1)
            val caller = Thread({
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                answer = try {
                    binder!!.transact(CODE_PROBE, data, reply, 0)
                    reply.readString() ?: "sealed: 没有结果"
                } catch (e: android.os.RemoteException) {
                    // the probe process died: the driver crashed compiling
                    val kernel = runCatching { stageFile.readText().trim() }.getOrDefault("")
                    "sealed: GPU 驱动编译 ${kernel.ifEmpty { "着色器" }} 时崩溃"
                } finally {
                    data.recycle()
                    reply.recycle()
                }
                done.countDown()
            }, "gpu-probe")
            caller.start()
            if (!done.await(COMPILE_SECONDS, TimeUnit.SECONDS)) {
                // stuck in the driver: end that process (same app, so allowed)
                runCatching {
                    context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                        ?.filter { it.processName.endsWith(PROCESS_SUFFIX) }
                        ?.forEach { android.os.Process.killProcess(it.pid) }
                }
                val kernel = runCatching { stageFile.readText().trim() }.getOrDefault("")
                return "sealed: GPU 驱动编译 ${kernel.ifEmpty { "着色器" }} 超时"
            }
            return answer ?: "sealed: 没有结果"
        } catch (e: Throwable) {
            return "sealed: ${e.javaClass.simpleName}"
        } finally {
            runCatching { context.unbindService(connection) }
            callbacks.shutdown()
        }
    }

    private fun key(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "$version|${Build.FINGERPRINT}"
    }

    internal fun stageFile(context: Context) = File(context.filesDir, "gpu_probe_stage")

    /** The process the probe runs in. */
    const val PROCESS_SUFFIX = ":gpuprobe"

    /**
     * Builds the whole engine, merge kernels included, and says how it went; then the
     * process ends. Each kernel's name is written to a file before it compiles, so a
     * crash leaves behind which one it was.
     */
    class Probe : Service() {
        private val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != CODE_PROBE) return super.onTransact(code, data, reply, flags)
                val file = stageFile(this@Probe)
                FilmNative.stageSink = { stage -> runCatching { file.writeText(stage) } }
                val result = try {
                    val handle = FilmNative.create(assets, true)
                    FilmNative.destroy(handle)
                    "ok"
                } catch (e: Throwable) {
                    "sealed: ${e.message ?: e.javaClass.simpleName}"
                }
                reply?.writeString(result)
                // done with the driver: this process has no further use
                android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
                return true
            }
        }

        override fun onBind(intent: Intent?): IBinder = binder
    }
}
