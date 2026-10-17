package com.controletv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** Film-style subtitle text: white letters with a black outline, readable without a box behind. */
class OutlinedTextView(context: Context) : TextView(context) {
    private var drawingOutline = false

    override fun onDraw(canvas: Canvas) {
        val fill = currentTextColor
        // First pass: thick black stroke; second pass: the white letters on top
        drawingOutline = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = textSize * 0.14f
        setTextColor(Color.BLACK)
        super.onDraw(canvas)
        drawingOutline = false
        paint.style = Paint.Style.FILL
        setTextColor(fill)
        super.onDraw(canvas)
    }

    // setTextColor inside onDraw would otherwise ask for another layout/draw pass
    override fun requestLayout() {
        if (!drawingOutline) super.requestLayout()
    }

    override fun invalidate() {
        if (!drawingOutline) super.invalidate()
    }
}
