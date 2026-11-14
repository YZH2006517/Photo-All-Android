package org.photoall.film

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.engine.DevelopOptions
import org.photoall.film.engine.FilmEngine
import kotlin.concurrent.thread

/** Engine test bench: pick a photo, step through stocks, develop on the GPU. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private var engine: FilmEngine? = null
    private var source: Bitmap = testChart()
    private var film = 4
    private var longEdge = 4096
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        org.photoall.film.ui.Style.apply(this)
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(24, 24, 24, 24) }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val buttons = LinearLayout(this).apply {
            addView(button("选照片") { pickPhoto() })
            addView(button("上一卷") { film = (film + FilmEngine.filmStocks.size - 1) % FilmEngine.filmStocks.size; develop() })
            addView(button("下一卷") { film = (film + 1) % FilmEngine.filmStocks.size; develop() })
            addView(button("冲洗") { develop() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(preview)
            addView(buttons)
            // targetSdk 35+ draws edge to edge: keep content clear of the system bars
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        // adb bench: am start -n org.photoall.film/.MainActivity --es image bench.jpg --ei edge 4096
        intent.getStringExtra("image")?.let { name ->
            source = BitmapFactory.decodeFile(java.io.File(getExternalFilesDir(null), name).path) ?: source
        }
        longEdge = intent.getIntExtra("edge", longEdge)
        status.text = "正在启动 GPU…"
        thread {
            val message = try {
                engine = FilmEngine.create(assets)
                "GPU: ${engine!!.deviceName}"
            } catch (e: Throwable) {
                "引擎无法启动：${e.message}"
            }
            runOnUiThread { status.text = message; if (engine != null) develop() }
        }
    }

    override fun onDestroy() {
        engine?.close()
        super.onDestroy()
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        develop()
    }

    private fun pickPhoto() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
    }

    private fun develop() {
        val engine = engine ?: return
        if (busy) return
        busy = true
        val stock = FilmEngine.filmStocks[film]
        status.text = "冲洗中：$stock"
        thread {
            val message = try {
                val start = System.nanoTime()
                val result = engine.develop(source, mapOf("film" to film.toFloat()), DevelopOptions(maxLongEdge = longEdge))
                val ms = (System.nanoTime() - start) / 1e6
                runOnUiThread { preview.setImageBitmap(result.bitmap) }
                "%s · %dx%d · %d 道工序 · GPU %.0f ms · 总 %.0f ms · 显存峰值 %d MiB".format(
                    stock, result.bitmap.width, result.bitmap.height, result.passes, result.gpuMs, ms,
                    result.peakImageBytes / (1024 * 1024))
            } catch (e: Throwable) {
                "冲洗失败：${e.message}"
            }
            Log.i("FilmBench", message)
            runOnUiThread { status.text = message; busy = false }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    /** Hue sweep over a grey ramp, so a stock change is visible without a photo. */
    private fun testChart(): Bitmap {
        val bitmap = Bitmap.createBitmap(1600, 1067, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val hues = intArrayOf(0xffd03030.toInt(), 0xffd0b030.toInt(), 0xff30a040.toInt(),
            0xff3070d0.toInt(), 0xffa040c0.toInt(), 0xffd03030.toInt())
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 1600f, 0f, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1600f, 700f, paint)
        paint.shader = LinearGradient(0f, 0f, 1600f, 0f, 0xff000000.toInt(), 0xffffffff.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 700f, 1600f, 1067f, paint)
        return bitmap
    }
}
