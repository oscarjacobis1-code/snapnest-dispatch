package com.snapnest.dispatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

class SnapNestLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val brand: Drawable? = context.getDrawable(R.drawable.snapnest_brand)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height)
        if (size <= 0) return
        val left = (width - size) / 2
        val top = (height - size) / 2
        brand?.setBounds(left, top, left + size, top + size)
        brand?.draw(canvas)
    }
}
