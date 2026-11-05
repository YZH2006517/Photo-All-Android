package org.photoall.film.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Row-major 3x3 helpers. */
internal object Mat {
    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }
    fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }
    fun invert(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-12) return null
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }
    fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(9) { a[it] + (b[it] - a[it]) * t }
    fun diag(x: Double, y: Double, z: Double) = doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z)
    fun of(t: ColorSpaceTransform?): DoubleArray? {
        t ?: return null
        val r = arrayOfNulls<Rational>(9)
        t.copyElements(r, 0)
        return DoubleArray(9) { r[it]!!.toDouble() }
    }

    private val bradfordM = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
    fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val s = apply(bradfordM, from); val d = apply(bradfordM, to)
        return mul(invert(bradfordM)!!, mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), bradfordM))
    }
    val D50 = doubleArrayOf(0.96422, 1.0, 0.82521)
    val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)
    val XYZ_TO_SRGB = doubleArrayOf(3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252)
}

/**
 * Colour calibration of one camera from its Camera2 characteristics: the sensor's
 * response to Planckian light (the AWB CT curve) and the colour matrix that takes
 * white-balanced camera RGB to linear sRGB, both interpolated in mired between the
 * two reference illuminants as DNG does.
 */
class Calibration(get: (CameraCharacteristics.Key<*>) -> Any?) {
    private val t1: Double
    private val t2: Double
    private val cm1: DoubleArray
    private val cm2: DoubleArray
    private val fm1: DoubleArray?
    private val fm2: DoubleArray?
    val valid: Boolean

    /** (ct, r/g, b/g) of a grey surface under a black body, 2000..12000 K. */
    val ctCurve: List<Triple<Double, Double, Double>>

    init {
        @Suppress("UNCHECKED_CAST")
        fun <T> key(k: CameraCharacteristics.Key<T>) = get(k) as T?
        val i1 = key(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
        val i2 = (key(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) as Number?)?.toInt()
        val c1 = Mat.of(key(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
        val c2 = Mat.of(key(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)) ?: c1
        val k1 = Mat.of(key(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)) ?: Mat.diag(1.0, 1.0, 1.0)
        val k2 = Mat.of(key(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)) ?: k1
        valid = c1 != null && i1 != null
        t1 = illuminantKelvin(i1 ?: 21)
        t2 = illuminantKelvin(i2 ?: i1 ?: 21)
        cm1 = Mat.mul(k1, c1 ?: Mat.diag(1.0, 1.0, 1.0))
        cm2 = Mat.mul(k2, c2 ?: Mat.diag(1.0, 1.0, 1.0))
        fm1 = Mat.of(key(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        fm2 = Mat.of(key(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)) ?: fm1
        ctCurve = (2000..12000 step 100).map { t ->
            val cam = Mat.apply(xyzToCamera(t.toDouble()), planckXyz(t.toDouble()))
            Triple(t.toDouble(), cam[0] / cam[1], cam[2] / cam[1])
        }
    }

    // DNG weighting: linear in 1/T between the two references.
    private fun weight(t: Double): Double {
        if (abs(1 / t1 - 1 / t2) < 1e-9) return 0.0
        return ((1 / t - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0)
    }

    fun xyzToCamera(t: Double) = Mat.lerp(cm2, cm1, weight(t))

    /** White-balanced camera RGB (grey = 1,1,1) -> linear sRGB D65. */
    fun cameraToSrgb(t: Double): DoubleArray {
        val toD65 = if (fm1 != null && fm2 != null) {
            Mat.mul(Mat.bradford(Mat.D50, Mat.D65), Mat.lerp(fm2, fm1, weight(t)))
        } else {
            // camera = CM * XYZ; balanced c' = c / neutral -> XYZ = CM^-1 * diag(neutral) * c'
            val cm = xyzToCamera(t)
            val neutral = Mat.apply(cm, planckXyz(t)).let { n -> doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1]) }
            val m = Mat.mul(Mat.invert(cm) ?: Mat.diag(1.0, 1.0, 1.0), Mat.diag(neutral[0], 1.0, neutral[2]))
            val white = Mat.apply(m, doubleArrayOf(1.0, 1.0, 1.0)).let { w -> DoubleArray(3) { w[it] / w[1] } }
            Mat.mul(Mat.bradford(white, Mat.D65), m)
        }
        return Mat.mul(Mat.XYZ_TO_SRGB, toD65)
    }

    /** r/g and b/g of grey at colour temperature t, off the curve by none. */
    fun pointAt(t: Double): Pair<Double, Double> {
        val c = ctCurve
        if (t <= c.first().first) return c.first().second to c.first().third
        if (t >= c.last().first) return c.last().second to c.last().third
        val i = c.indexOfFirst { it.first >= t }
        val a = c[i - 1]; val b = c[i]
        val f = (t - a.first) / (b.first - a.first)
        return (a.second + (b.second - a.second) * f) to (a.third + (b.third - a.third) * f)
    }

    companion object {
        /** CIE xy of a black body (Kim et al. cubic spline), as XYZ with Y = 1. */
        fun planckXyz(t: Double): DoubleArray {
            val tt = t.coerceIn(1667.0, 25000.0)
            val x = if (tt <= 4000) -0.2661239e9 / (tt * tt * tt) - 0.2343589e6 / (tt * tt) + 0.8776956e3 / tt + 0.179910
            else -3.0258469e9 / (tt * tt * tt) + 2.1070379e6 / (tt * tt) + 0.2226347e3 / tt + 0.240390
            val y = when {
                tt <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                tt <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            return doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        }

        /** Camera2 / EXIF light source code -> correlated colour temperature. */
        fun illuminantKelvin(code: Int): Double = when (code) {
            1, 4, 9, 20 -> 5503.0
            2 -> 4230.0
            3 -> 2850.0
            10 -> 6500.0
            11 -> 7500.0
            12 -> 6430.0
            13 -> 5000.0
            14 -> 4150.0
            15 -> 3525.0
            17 -> 2856.0
            18 -> 4874.0
            19 -> 6774.0
            21 -> 6504.0
            22 -> 7504.0
            23 -> 5003.0
            24 -> 3200.0
            else -> 6504.0
        }

        fun clampGain(g: Double) = min(max(g, 0.25), 8.0)
    }
}
