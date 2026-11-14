package org.photoall.film

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What goes wrong on phones we do not have, caught, survived where that is safe, and
 * reported. Nothing leaves the phone by itself: reports stay in the app's storage and
 * the next launch offers them to copy and send (an issue, a message).
 *
 * - An error on a background thread (a decode, a save, a camera or GPU callback) is
 *   recorded and the app goes on; [onRecovered] lets the screen restart what died.
 * - An error on the main thread outside a screen's start or end (a tap, a callback, a
 *   posted task) is recorded and the main loop goes on. One inside a screen's lifecycle
 *   leaves that screen half-made, and errors that keep coming (more than 3 in 10 s)
 *   would only loop: those still end the app, after the report is written.
 * - Native crashes (the GPU driver, a camera HAL) and the system ending the app (memory,
 *   no response) cannot be caught in the process: Android 11+ keeps the reason, read on
 *   the next launch ([ActivityManager.getHistoricalProcessExitReasons]), with the native
 *   backtrace on Android 12+. [stage] is written synchronously before risky steps, so
 *   the report says what the app was doing.
 * - Two fatal ends within 15 minutes: the next launch asks for safe settings ([Report.safeMode]).
 */
object CrashReport {
    private const val TAG = "CrashReport"
    private const val PREFS = "crash"
    @Volatile private var app: Context? = null
    /** The application, for code with no context of its own (the frame renderer's fonts). */
    val context: Context? get() = app
    private val main = Handler(Looper.getMainLooper())
    private val recoveries = ArrayDeque<Long>()

    /** Main thread: an error was survived on [thread] (the screen may restart what it ran). */
    @Volatile var onRecovered: ((thread: String, error: Throwable) -> Unit)? = null

    class Report(val title: String, val text: String, val fatal: Boolean, val safeMode: Boolean)

    private fun fatalFile(context: Context) = File(context.filesDir, "crash/last.txt")
    private fun recoveredFile(context: Context) = File(context.filesDir, "crash/recovered.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        this.app = app
        // the last step of the run before this one, kept for a report of how that run ended
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
            // The last run died compiling a merge kernel: the driver can't take them, so
            // leave them out on this phone until the app or the system changes.
            val died = p.getString("engine_stage", null)
            if (died != null && died.startsWith("hp_"))
                p.edit().putString("burst_off", burstKey(app)).putString("burst_off_kernel", died).commit()
            p.edit().putString("prev_stage", p.getString("stage", null)).putLong("prev_stage_at", p.getLong("stage_at", 0L))
                .putString("prev_engine_stage", p.getString("engine_stage", null))
                .putString("stage", "启动").putLong("stage_at", System.currentTimeMillis())
                .remove("engine_stage").commit()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread !== mainThread && !tooMany()) {
                recovered(thread.name, error)
                return@setDefaultUncaughtExceptionHandler // the app goes on; that thread is done
            }
            writeFatal(app, thread, error)
            previous?.uncaughtException(thread, error)
        }
        // The main loop, guarded: an error escaping a task on the main thread lands here
        // instead of ending the app, and the loop is entered again.
        main.post {
            while (true) {
                try {
                    Looper.loop()
                    return@post // the loop was quit: nothing more to run
                } catch (error: Throwable) {
                    if (inLifecycle(error) || tooMany()) {
                        writeFatal(app, mainThread, error)
                        previous?.uncaughtException(mainThread, error) ?: throw error
                        return@post
                    }
                    recovered("main", error)
                }
            }
        }
    }

    /** A survived error: recorded for the next launch, and the user told once, gently. */
    private fun recovered(thread: String, error: Throwable) {
        Log.e(TAG, "recovered on $thread", error)
        synchronized(recoveries) { recoveries.addLast(System.currentTimeMillis()) }
        val context = app ?: return
        runCatching {
            val f = recoveredFile(context)
            f.parentFile?.mkdirs()
            if (f.length() > 48_000) f.writeText("")
            f.appendText(header(context, "已自动恢复的错误（$thread）") + "\n" + stack(error) + "\n\n")
        }
        main.post {
            runCatching {
                android.widget.Toast.makeText(context, "出了点小问题，已自动恢复（报告已保存）", android.widget.Toast.LENGTH_SHORT).show()
            }
            runCatching { onRecovered?.invoke(thread, error) }
        }
    }

    /** More than 3 errors survived in 10 s: something keeps failing; going on would only loop. */
    private fun tooMany(): Boolean = synchronized(recoveries) {
        val now = System.currentTimeMillis()
        while (recoveries.isNotEmpty() && now - recoveries.first() > 10_000) recoveries.removeFirst()
        recoveries.size >= 3
    }

    /** Thrown while a screen (or the app) was being made, shown, hidden or ended. */
    private fun inLifecycle(error: Throwable): Boolean {
        val marks = listOf("performLaunchActivity", "handleLaunchActivity", "performResumeActivity",
            "handleResumeActivity", "performCreate", "performStart", "performRestart", "handleRelaunchActivity",
            "performPauseActivity", "handlePauseActivity", "performStopActivity", "handleStopActivity",
            "performDestroyActivity", "handleDestroyActivity", "handleBindApplication", "handleCreateService",
            "installProvider", "callActivityOnSaveInstanceState")
        var e: Throwable? = error
        while (e != null) {
            if (e is android.util.AndroidRuntimeException && e.javaClass.simpleName == "SuperNotCalledException") return true
            if (e.stackTrace.any { frame -> frame.className.startsWith("android.app.") && marks.any { frame.methodName.contains(it) } }) return true
            e = e.cause
        }
        return false
    }

    private fun writeFatal(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val f = fatalFile(context)
            f.parentFile?.mkdirs()
            f.writeText(header(context, "闪退（${thread.name}）") + "\n" + stack(error))
        }
    }

    /** The step being taken, kept on disk at once so even a native crash leaves it behind. */
    fun stage(context: Context, what: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", what).putLong("stage_at", System.currentTimeMillis()).commit()
    }

    /**
     * The GPU engine's own step (the kernel being compiled), kept apart from [stage]: the
     * engine starts on its own thread while the camera binds, and would be written over.
     */
    fun engineStage(what: String) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("engine_stage", what)?.commit()
    }

    private fun burstKey(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "$version|${Build.FINGERPRINT}"
    }

    /**
     * Whether the engine may compile the HDR+ merge kernels: not after a run died compiling
     * one on this app version and system build (a driver bug; RAW bursts then use one frame).
     */
    fun burstKernelsAllowed(): Boolean {
        val app = app ?: return true
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("burst_off", null) != burstKey(app)
    }

    private val events = ArrayDeque<String>()

    /** A line of what the user saw (status messages), the last 60 kept for diagnostics. */
    fun event(text: String) {
        if (text.isBlank()) return
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + text.take(200)
        synchronized(events) {
            if (events.lastOrNull()?.endsWith(text.take(200)) == true) return
            events.addLast(line)
            while (events.size > 60) events.removeFirst()
        }
    }

    fun recentEvents(): List<String> = synchronized(events) { events.toList() }

    /** The handled errors recorded since the last report (not cleared). */
    fun recentNotes(context: Context): String =
        runCatching { recoveredFile(context).takeIf { it.exists() }?.readText()?.takeLast(6000) }.getOrNull().orEmpty()

    /** [stage] without a context at hand (the engine); nothing before [install]. */
    fun stage(what: String) { app?.let { stage(it, what) } }

    /** The GPU the engine runs on (for the report header). */
    fun gpuName(name: String) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("gpu", name)?.apply()
    }

    /** Something that failed and was handled where it happened, worth reporting all the same. */
    fun note(context: Context, what: String, error: Throwable) {
        runCatching {
            val f = recoveredFile(context)
            f.parentFile?.mkdirs()
            if (f.length() > 48_000) f.writeText("")
            f.appendText(header(context, what) + "\n" + stack(error) + "\n\n")
        }
    }

    /**
     * The report of what went wrong since it was last shown, or null. Reading it marks it
     * shown (a report is offered once).
     */
    fun pending(context: Context): Report? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fatal = mutableListOf<String>()
        val f = fatalFile(context)
        if (f.exists()) {
            fatal += runCatching { f.readText() }.getOrDefault("")
            f.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val seen = prefs.getLong("exit_seen", 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exit_seen", it).apply() }
            // the GPU probe's process is meant to take a driver crash: not the app's end
            val bad = exits.filterNot { it.processName.endsWith(org.photoall.film.engine.GpuProbe.PROCESS_SUFFIX) }.firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    (it.reason == ApplicationExitInfo.REASON_CRASH && fatal.isEmpty())
            }
            if (bad != null) fatal += exitText(context, bad)
        }
        val r = recoveredFile(context)
        val survived = if (r.exists()) runCatching { r.readText().takeLast(8000) }.getOrDefault("").also { r.delete() } else ""
        if (fatal.isEmpty() && survived.isBlank()) return null

        // two fatal ends close together: the next start asks for safe settings
        var safe = false
        if (fatal.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val times = (prefs.getString("fatal_times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
                .filter { now - it < 15 * 60_000 } + now
            safe = times.size >= 2
            prefs.edit().putString("fatal_times", if (safe) "" else times.joinToString(",")).apply()
        }
        val text = (fatal + listOfNotNull(survived.takeIf { it.isNotBlank() }?.let { "—— 运行中已自动恢复的错误 ——\n$it" }))
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val title = if (fatal.isNotEmpty()) "上次意外退出了" else "上次运行时出了点问题"
        return Report(title, text, fatal.isNotEmpty(), safe)
    }

    /** Debug: the newest native crash on record again, shown or not (to check the report). */
    fun latestNative(context: Context): Report? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java)
        val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE } ?: return null
        return Report("最近一次原生崩溃（调试）", exitText(context, e), true, false)
    }

    private fun exitText(context: Context, e: ApplicationExitInfo): String {
        val why = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃（GPU 引擎或相机驱动）"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
            ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
            else -> "闪退"
        }
        val b = StringBuilder(header(context, why, e.timestamp, previousRun = true))
        b.append("系统描述：").append(e.description ?: "—").append('\n')
        b.append("占用内存：PSS ").append(e.pss / 1024).append(" MB · RSS ").append(e.rss / 1024).append(" MB\n")
        // The native tombstone (Android 12+) is a protobuf; its readable strings carry the
        // signal, the abort message and the backtrace's libraries and functions.
        if (Build.VERSION.SDK_INT >= 31 && (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR))
            runCatching {
                e.traceInputStream?.use { input ->
                    val bytes = input.readBytes()
                    // a native crash: the tombstone read properly (the crashing thread, the
                    // last log lines); an ANR's trace is text already
                    val text = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) Tombstone.describe(bytes) else null
                    b.append('\n').append(text ?: ("—— 回溯 ——\n" + readable(bytes, 6000)))
                }
            }
        return b.toString()
    }

    /** Printable runs of a binary blob, one per line, the first [limit] characters. */
    private fun readable(bytes: ByteArray, limit: Int): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 5) out.append(run).append('\n')
            run.setLength(0)
        }
        for (c in String(bytes, Charsets.UTF_8)) {
            if (c == '\n' || c.isISOControl() || c == '�') flush() else run.append(c)
            if (out.length > limit) break
        }
        flush()
        return out.toString().take(limit)
    }

    /** [previousRun]: about the run before this one (its last step, not this run's). */
    internal fun header(context: Context, what: String, at: Long = System.currentTimeMillis(), previousRun: Boolean = false): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrDefault("?")
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val stageKey = if (previousRun) "prev_stage" else "stage"
        val stageAt = prefs.getLong("${stageKey}_at", 0L)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            "共 ${info.totalMem / 1_048_576} MB · 可用 ${info.availMem / 1_048_576} MB"
        }.getOrDefault("?")
        return buildString {
            append("Photo All 问题报告 · ").append(what).append('\n')
            append("时间：").append(stamp.format(Date(at))).append('\n')
            append("版本：").append(version).append('\n')
            append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("（").append(Build.DEVICE).append("）\n")
            append("芯片：").append(soc).append(" · GPU：").append(prefs.getString("gpu", "未知")).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.DISPLAY).append('\n')
            append("内存：").append(memory).append('\n')
            append("最后一步：").append(prefs.getString(stageKey, null) ?: "—")
            if (stageAt > 0) append("（").append(stamp.format(Date(stageAt))).append("）")
            append('\n')
            prefs.getString(if (previousRun) "prev_engine_stage" else "engine_stage", null)?.let {
                append("GPU 引擎：").append(if (it in setOf("vulkan", "ready", "failed")) it else "编译 $it").append('\n')
            }
            if (prefs.getString("burst_off", null) == burstKey(context))
                append("多帧合成：已关闭（GPU 驱动编译 ").append(prefs.getString("burst_off_kernel", "hp_*"))
                    .append(" 时崩溃，RAW 改用单帧）\n")
            else org.photoall.film.engine.GpuProbe.verdict(context)?.takeIf { it.startsWith("sealed") }?.let {
                append("多帧合成：已封存（").append(it.removePrefix("sealed: ")).append("，RAW 改用单帧）\n")
            }
        }
    }

    private fun stack(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().take(12000)
}
