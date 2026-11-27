package org.photoall.film.collage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一键拼贴: one photo laid as the main print, the subjects of a handful of others cut out as
 * stickers ([cut]) and set round it ([arrange]): the big ones first, each where it covers the
 * least of the stickers already down and of the print's middle, drawn towards the print's edge
 * so the whole sits together as one heap. A new [seed] gives another arrangement.
 */
object AutoCollage {
    /** Most photos to cut at once: each is a run of the model and a sticker in memory. */
    const val MOST = 12

    class Cut(val stickers: List<Bitmap>, val missed: Int)

    /**
     * The subject of each photo, as a white-edged sticker kept in the library (自动), and for the
     * board a copy whose long side is at most 900 px (on a 2400 px collage a sticker is rarely a
     * third of it). [step] is told how many are done.
     * Worker thread.
     */
    fun cut(context: Context, uris: List<Uri>, step: (Int) -> Unit): Cut {
        val out = ArrayList<Bitmap>()
        var missed = 0
        for ((i, uri) in uris.withIndex()) {
            if (Thread.currentThread().isInterrupted) break
            val sticker = runCatching {
                val photo = Stickers.decode(context, uri, 1400) ?: return@runCatching null
                val cut = Segmenter.cut(context, photo).also { photo.recycle() } ?: return@runCatching null
                // a speck is not a subject found: no sticker of it
                val share = cut.width.toFloat() * cut.height / (1400f * 1400f)
                if (share < 0.004f) { cut.recycle(); return@runCatching null }
                // kept in the library's 自动 folder, to be used again
                val kept = Stickers.add(context, cut, Stickers.Edge.WHITE, 1f, auto = true).also { cut.recycle() }
                Stickers.load(context, kept.id)?.let { shrink(it, 900) }
            }.getOrNull()
            if (sticker != null) out += sticker else missed++
            step(i + 1)
        }
        return Cut(out, missed)
    }

    private fun shrink(b: Bitmap, edge: Int): Bitmap {
        val k = edge.toFloat() / max(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true).also { b.recycle() }
    }

    /**
     * The board laid out: the first print in the middle, a touch turned; every sticker sized for
     * how many there are and placed round it. Other prints are left where they are.
     */
    fun arrange(board: CollageActivity.Board, seed: Long) {
        val rnd = java.util.Random(seed)
        fun span(s: Float) = (rnd.nextFloat() - 0.5f) * s
        val H = CollageActivity.Board.ASPECT
        val W = max(board.width, 1000).toFloat()
        val stickers = board.layers.filterIsInstance<CollageActivity.Board.Sticker>()
        val n = stickers.size
        val main = board.layers.firstOrNull { it is CollageActivity.Board.Shot }
        // the print: smaller the more stickers there are to go round it
        var px = 0.5f; var py = H / 2; var phw = 0f; var phh = 0f
        if (main != null) {
            main.w = if (n <= 3) 0.62f else if (n <= 7) 0.56f else 0.5f
            main.rot = span(5f)
            // with stickers, the print a little up: room below for the heap to settle
            main.cx = 0.5f + span(0.04f); main.cy = H / 2 - (if (n > 0) 0.03f else 0f) + span(0.04f)
            val (hw, hh) = main.half(W)
            px = main.cx; py = main.cy; phw = hw; phh = hh
        }
        if (n == 0) { board.invalidate(); return }
        val base = (0.40f * n.toFloat().pow(-0.3f)).coerceIn(0.18f, 0.38f)
        // the big ones first: they have the most room to choose from
        val order = stickers.map { it to base * (0.85f + rnd.nextFloat() * 0.3f) }.sortedByDescending { it.second }
        class Box(val x: Float, val y: Float, val hw: Float, val hh: Float)
        val placed = ArrayList<Box>()
        fun overlap(ax: Float, ay: Float, ahw: Float, ahh: Float, b: Box): Float {
            val ox = min(ax + ahw, b.x + b.hw) - max(ax - ahw, b.x - b.hw)
            val oy = min(ay + ahh, b.y + b.hh) - max(ay - ahh, b.y - b.hh)
            return if (ox > 0 && oy > 0) ox * oy else 0f
        }
        // the print's middle, which a sticker should leave clear; its edge, which they lean on
        val middle = Box(px, py, phw * 0.62f, phh * 0.62f)
        for ((s, size) in order) {
            val aspect = s.bmp.height.toFloat() / s.bmp.width
            // a tall one no taller than a wide one is wide
            s.w = if (aspect > 1.25f) size * 1.25f / aspect else size
            val hw = s.w / 2 * 0.82f; val hh = s.w / 2 * aspect * 0.82f // the corners of a cut-out are clear
            var best = Float.MAX_VALUE; var bx = 0.5f; var by = H / 2
            repeat(360) {
                val x = 0.04f + hw + rnd.nextFloat() * max(0.001f, 1f - 0.08f - 2 * hw)
                val y = 0.04f + hh + rnd.nextFloat() * max(0.001f, H - 0.08f - 2 * hh)
                var score = 0f
                for (b in placed) score += overlap(x, y, hw, hh, b) * 4f
                if (main != null) {
                    score += overlap(x, y, hw, hh, middle) * 3f
                    // how far from the print's edge (outside or in), as a share of the board
                    val dx = kotlin.math.abs(x - px) - phw; val dy = kotlin.math.abs(y - py) - phh
                    val out = kotlin.math.hypot(max(dx, 0f), max(dy, 0f))
                    val edge = if (dx <= 0 && dy <= 0) -max(dx, dy) else out
                    score += edge * edge * 0.6f
                }
                score += rnd.nextFloat() * 0.002f
                if (score < best) { best = score; bx = x; by = y }
            }
            s.cx = bx; s.cy = by; s.rot = span(34f)
            placed += Box(bx, by, hw, hh)
        }
        // on the board: the print under, the stickers over, in the order placed
        if (main != null) board.toBottom(main)
        for ((s, _) in order) board.toTop(s)
        board.invalidate()
    }
}
