package com.snapnest.dispatch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SnapNestLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val navy = Color.rgb(10, 48, 103)
    private val navyDark = Color.rgb(4, 39, 86)
    private val blue = Color.rgb(43, 137, 255)
    private val orange = Color.rgb(255, 106, 0)
    private val white = Color.rgb(250, 250, 249)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        if (s <= 0f) return

        val left = (w - s) / 2f
        val top = (h - s) / 2f
        val outer = RectF(left + s * .04f, top + s * .04f, left + s * .96f, top + s * .96f)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom,
            navy, navyDark, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(outer, s * .18f, s * .18f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * .055f
        paint.color = white
        val frame = RectF(left + s * .12f, top + s * .12f, left + s * .88f, top + s * .88f)
        canvas.drawRoundRect(frame, s * .14f, s * .14f, paint)

        paint.style = Paint.Style.FILL
        paint.color = white
        val inner = RectF(left + s * .16f, top + s * .29f, left + s * .84f, top + s * .82f)
        canvas.drawRoundRect(inner, s * .08f, s * .08f, paint)

        paint.color = blue
        val cy = top + s * .22f
        for (cx in listOf(.27f, .39f, .51f)) {
            canvas.drawCircle(left + s * cx, cy, s * .035f, paint)
        }

        textPaint.color = navyDark
        textPaint.textSize = s * .32f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val baseline = top + s * .67f
        canvas.drawText("SN", left + s * .50f, baseline, textPaint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = s * .055f
        paint.color = orange
        val path = Path().apply {
            moveTo(left + s * .26f, top + s * .72f)
            lineTo(left + s * .50f, top + s * .81f)
            lineTo(left + s * .75f, top + s * .72f)
        }
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }
}
