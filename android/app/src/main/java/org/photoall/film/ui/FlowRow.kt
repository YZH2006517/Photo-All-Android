package org.photoall.film.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Children laid out left to right, running onto the next line when the width is used up,
 * so a row of chips is never cut off at the screen's edge on a narrow phone.
 */
class FlowRow(context: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams
            c.measure(getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, lp.width),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, lp.height))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += line + gapY; line = 0 }
            x += c.measuredWidth + gapX
            line = maxOf(line, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + line + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += line + gapY; line = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapX
            line = maxOf(line, c.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
