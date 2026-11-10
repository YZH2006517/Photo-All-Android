package org.photoall.film.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Vibration as confirmation only. Each [Event] has one feel, played the best way the
 * phone allows:
 *
 * 1. the platform's haptic primitives (API 30+), only on a motor that reports amplitude
 *    control as well as every primitive the event needs. Some skins list primitives the
 *    motor cannot compose (RedMagic lists nine, composes none): those drop the effect
 *    without a word, so a motor without amplitude control never gets compositions;
 * 2. otherwise the predefined effects TICK, CLICK, HEAVY_CLICK and DOUBLE_CLICK. The
 *    framework plays them with a built-in waveform where the motor has no effect of its
 *    own, so they sound on every phone. Not the view's haptic constants: CLOCK_TICK and
 *    SEGMENT_TICK became TEXTURE_TICK, which many motors do not have and which the
 *    framework then drops (performHapticFeedback still says it played);
 * 3. a motor without amplitude control plays every event too (earlier only the
 *    essential ones, which left a RedMagic all but silent), ticks spaced a little wider
 *    so a fast drag does not run together into a buzz.
 *
 * The motor shakes the phone and its stabiliser: while [quiet] (a frame is exposing)
 * nothing plays; the shutter's click comes once the last frame is taken.
 */
class Haptics(private val view: View, private val level: () -> Int) {
    enum class Event(val essential: Boolean) {
        SHUTTER(true),      // the last frame of a shot is taken
        TICK(false),        // a third of a stop on a dial
        STOP(true),         // a whole stop
        LIMIT(true),        // the end of a dial's range
        FOCUSED(false),     // a tap's focus scan found focus
        FOCUS_FAILED(false),
        LOCKED(false),      // focus held (a double tap)
        SEGMENT(false),     // a choice in a set: flash mode, lens
        DEVELOPED(false),   // a photo is saved (only while the camera is shown)
        ERROR(true),        // a shot failed
    }

    /** Set while a frame is exposing: nothing plays. */
    @Volatile var quiet = false

    private val vibrator: Vibrator? = run {
        val context = view.context
        val v = if (Build.VERSION.SDK_INT >= 31)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.takeIf { it.hasVibrator() }
    }
    private val amplitude = vibrator?.hasAmplitudeControl() == true
    /** Compositions only where they will really play (see the class note). */
    private val compose get() = amplitude && Build.VERSION.SDK_INT >= 30 && primitives.containsAll(listOf(
        VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK))
    private val primitives: Set<Int> = run {
        val v = vibrator ?: return@run emptySet()
        if (Build.VERSION.SDK_INT < 30) return@run emptySet()
        val wanted = mutableListOf(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += VibrationEffect.Composition.PRIMITIVE_THUD
            wanted += VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        }
        val ok = v.arePrimitivesSupported(*wanted.toIntArray())
        wanted.filterIndexed { i, _ -> ok[i] }.toSet()
    }
    private var lastTick = 0L

    /** How the phone will play: for the shot record and the settings' description. */
    val tier: String
        get() = when {
            vibrator == null -> "无振动马达"
            compose -> "原语"
            amplitude -> "预设效果"
            else -> "预设效果（无振幅控制）"
        }

    fun play(event: Event) {
        val level = level()
        if (level <= 0 || quiet) return
        val v = vibrator ?: return
        if (event == Event.TICK || event == Event.SEGMENT || event == Event.STOP) {
            val now = SystemClock.uptimeMillis()
            // a fast drag must not become a buzz; a simple motor rings on a little longer
            if (now - lastTick < (if (amplitude) 40 else 70)) return
            lastTick = now
        }
        val scale = if (level == 1) 0.6f else 1f
        if (compose) composition(event, scale)?.let { return vibrate(v, it) }
        vibrate(v, predefined(event))
    }

    // Tier 1: primitives, all of them supported, or null.
    private fun composition(event: Event, scale: Float): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 30) return null
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val thud = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_THUD else -1
        val low = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else -1
        val parts: List<Triple<Int, Float, Int>> = when (event) { // primitive, strength, delay ms
            Event.SHUTTER -> listOf(Triple(click, 0.8f, 0))
            Event.TICK -> listOf(Triple(tick, 0.3f, 0))
            Event.STOP -> listOf(Triple(tick, 0.7f, 0))
            Event.LIMIT -> listOf(Triple(if (thud in primitives) thud else click, 0.6f, 0))
            Event.FOCUSED -> listOf(Triple(tick, 0.5f, 0))
            Event.FOCUS_FAILED -> (if (low in primitives) low else tick).let { listOf(Triple(it, 0.5f, 0), Triple(it, 0.5f, 40)) }
            Event.LOCKED -> listOf(Triple(click, 0.7f, 0), Triple(tick, 0.35f, 30))
            Event.SEGMENT -> listOf(Triple(tick, 0.4f, 0))
            Event.DEVELOPED -> listOf(Triple(if (low in primitives) low else tick, 0.4f, 0))
            Event.ERROR -> listOf(Triple(click, 0.7f, 0), Triple(click, 0.7f, 60))
        }
        if (!parts.all { it.first in primitives }) return null
        val c = VibrationEffect.startComposition()
        for ((id, strength, delay) in parts) c.addPrimitive(id, (strength * scale).coerceIn(0f, 1f), delay)
        return c.compose()
    }

    // Tier 2 / 3: predefined effects (API 29); the framework falls back to its own waveform.
    private fun predefined(event: Event): VibrationEffect = VibrationEffect.createPredefined(when (event) {
        Event.SHUTTER, Event.LOCKED -> VibrationEffect.EFFECT_CLICK
        Event.LIMIT -> VibrationEffect.EFFECT_HEAVY_CLICK
        Event.FOCUS_FAILED, Event.ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
        else -> VibrationEffect.EFFECT_TICK
    })

    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else @Suppress("DEPRECATION") v.vibrate(effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }
}
