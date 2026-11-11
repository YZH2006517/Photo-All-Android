package org.photoall.film

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsStore

/**
 * What a tester copies when something does not work but nothing crashed (RAW refused, a
 * flash that does not fire): every camera as this phone presents it to apps (RAW, flash
 * and AE modes, hardware level, sizes), what the app learned about this phone, the
 * shooting settings, the last messages shown and the errors handled. No photos, no
 * account data.
 */
object Diagnostics {

    fun collect(context: Context): String = buildString {
        append(CrashReport.header(context, "诊断信息"))
        val store = SettingsStore(context)
        append("\n—— 拍摄设置 ——\n")
        append("RAW ").append(onOff(store.flag(Settings.RAW)))
            .append(" · HDR ").append(onOff(store.flag(Settings.HDR)))
            .append(" · 闪光 ").append(Settings.flashModes.getOrElse(store[Settings.FLASH].toInt()) { "?" })
            .append(" · 保存 RAW ").append(onOff(store.flag(Settings.KEEP_RAW)))
            .append(" · 冲洗长边 ").append(store.longEdge).append('\n')

        val quirks = context.getSharedPreferences("camera_quirks", Context.MODE_PRIVATE).all
        append("\n—— 本机已记下的兼容处理 ——\n")
        if (quirks.isEmpty()) append("无\n") else quirks.forEach { (k, v) -> append(k).append(" = ").append(v).append('\n') }

        append("\n—— 相机（应用看到的样子）——\n")
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        for (id in ids) {
            append(camera(manager, id, physical = false))
            seen += id
            val physical = runCatching {
                if (Build.VERSION.SDK_INT >= 28) manager.getCameraCharacteristics(id).physicalCameraIds else emptySet()
            }.getOrDefault(emptySet())
            for (p in physical) if (p !in seen) { append(camera(manager, p, physical = true)); seen += p }
        }
        if (ids.isEmpty()) append("（读不到相机列表）\n")

        val events = CrashReport.recentEvents()
        append("\n—— 最近的提示 ——\n")
        if (events.isEmpty()) append("无\n") else events.takeLast(40).forEach { append(it).append('\n') }

        val notes = CrashReport.recentNotes(context)
        append("\n—— 最近处理过的错误 ——\n").append(notes.ifBlank { "无\n" })
    }

    private fun onOff(on: Boolean) = if (on) "开" else "关"

    private fun camera(manager: CameraManager, id: String, physical: Boolean): String = runCatching {
        val c = manager.getCameraCharacteristics(id)
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            else -> "外接"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; else -> "?"
        }
        val caps = (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)).map {
            when (it) {
                0 -> "BACKWARD"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST"; 3 -> "RAW"; 5 -> "READ_SETTINGS"
                6 -> "BURST"; 8 -> "DEPTH"; 9 -> "HIGH_SPEED"; 11 -> "LOGICAL_MULTI"; 12 -> "MONO"
                16 -> "ULTRA_HIGH_RES"; else -> "#$it"
            }
        }
        val ae = (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)).map {
            when (it) { 0 -> "OFF"; 1 -> "ON"; 2 -> "AUTO_FLASH"; 3 -> "ALWAYS_FLASH"; 4 -> "REDEYE"; 5 -> "EXTERNAL"; 6 -> "LOW_LIGHT"; else -> "#$it" }
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(format: Int) = runCatching { map?.getOutputSizes(format)?.sortedByDescending { it.width.toLong() * it.height } }
            .getOrNull().orEmpty()
        val raw = sizes(ImageFormat.RAW_SENSOR)
        val jpeg = sizes(ImageFormat.JPEG)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { "%.2f".format(java.util.Locale.US, it) }
        buildString {
            append(if (physical) "  · 物理镜头 " else "镜头 ").append(id).append("（").append(facing).append(" · ").append(level)
                .append(" · 焦距 ").append(focal ?: "?").append(" mm）\n")
            append("    能力：").append(caps.joinToString(" ")).append('\n')
            append("    闪光灯：").append(if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "有" else "无")
                .append(" · AE 模式：").append(ae.joinToString(" ")).append('\n')
            append("    RAW：").append(if (raw.isEmpty()) "无" else raw.take(3).joinToString(" ") { "${it.width}x${it.height}" })
                .append(" · JPEG 最大：").append(jpeg.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "?").append('\n')
        }
    }.getOrElse { "镜头 $id：读取失败（${it.message}）\n" }
}
