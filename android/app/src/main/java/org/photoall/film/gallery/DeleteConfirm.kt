package org.photoall.film.gallery

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.ui.Sheet
import org.photoall.film.ui.Style
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Asking before photos go: a panel from below with the photos themselves (the first few,
 * fanned like prints, "+N" for the rest), what will happen to them — the app's own photos
 * are deleted for good, the rest go to the system bin for 30 days, their RAW with them —
 * and a cancel / delete pair, the delete in the danger colour.
 */
object DeleteConfirm {
    /** The danger colour: the same in every theme, white text on it reads at 4.5:1. */
    private const val DANGER = 0xffc8402a.toInt()
    private val loader = Executors.newSingleThreadExecutor()

    fun show(activity: Activity, photos: List<Photos.Photo>, onConfirm: () -> Unit) {
        if (photos.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val mine = photos.count { it.file != null }
        val shared = photos.size - mine
        val permanent = shared == 0
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(activity, if (permanent) "删除 ${photos.size} 张照片？" else "删除 ${photos.size} 张照片？", body)

        // the photos, fanned like a small stack of prints
        val stack = FrameLayout(activity)
        val shown = photos.take(4)
        val w = dp(66)
        val h = dp(88)
        shown.forEachIndexed { i, photo ->
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Style.SURFACE_2) }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                elevation = dp(2).toFloat() * (i + 1)
                rotation = (i - (shown.size - 1) / 2f) * 5f
            }
            stack.addView(image, FrameLayout.LayoutParams(w, h, Gravity.CENTER_VERTICAL).apply { leftMargin = i * dp(42) })
            loader.execute {
                val bitmap = Photos.thumbnail(activity, photo)
                activity.runOnUiThread { image.setImageBitmap(bitmap) }
            }
        }
        if (photos.size > shown.size) stack.addView(TextView(activity).apply {
            text = "+${photos.size - shown.size}"
            textSize = 14f
            typeface = Style.medium
            setTextColor(Style.TEXT)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Style.SURFACE_2) }
            elevation = dp(10).toFloat()
        }, FrameLayout.LayoutParams(dp(48), dp(36), Gravity.CENTER_VERTICAL).apply { leftMargin = shown.size * dp(42) + dp(30) })
        body.addView(stack, LinearLayout.LayoutParams(-2, dp(108)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        // what happens, one line per case
        fun line(text: String, color: Int, mark: Int) = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(mark) }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(10) })
            addView(TextView(activity).apply { this.text = text; textSize = 14f; setTextColor(color) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (mine > 0) body.addView(line(
            (if (shared > 0) "其中 $mine 张只存在本应用：" else "") + "直接删除，不能恢复", Style.TEXT, DANGER))
        if (shared > 0) body.addView(line(
            (if (mine > 0) "其余 $shared 张" else "") + "移到系统相册的回收站，30 天内可恢复", Style.TEXT, Style.TEXT_2))
        body.addView(line("同时拍下的 RAW 原片一起处理", Style.TEXT_2, Style.TEXT_3))

        // cancel / delete
        fun action(text: String, fill: Int, ink: Int, stroke: Int, onClick: () -> Unit) = Button(activity).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            typeface = Style.medium
            stateListAnimator = null
            Style.compact(this, 12f)
            background = Style.surface(activity, fill = fill, radiusDp = 14f, stroke = stroke)
            setTextColor(ink)
            Style.pressable(this)
            setOnClickListener { onClick() }
        }
        body.addView(LinearLayout(activity).apply {
            setPadding(0, dp(18), 0, dp(4))
            addView(action("取消", Color.TRANSPARENT, Style.TEXT, Style.HAIRLINE) { sheet.dismiss() },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
            addView(action(if (permanent) "永久删除" else if (mine > 0) "删除" else "移到回收站", DANGER, Color.WHITE, 0) {
                sheet.dismiss()
                onConfirm()
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        })
        sheet.show()
    }
}
