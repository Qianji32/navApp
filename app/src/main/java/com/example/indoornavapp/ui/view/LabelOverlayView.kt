package com.example.indoornavapp.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class LabelOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var labels: List<Building3DRenderer.ScreenLabel> = emptyList()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (label in labels) {
            when (label.type) {
                "stair"     -> { bgPaint.color = 0xDDE91E63.toInt(); textPaint.color = Color.WHITE }
                "elevator"  -> { bgPaint.color = 0xDD00BCD4.toInt(); textPaint.color = Color.WHITE }
                "entrance"  -> { bgPaint.color = 0xDD4CAF50.toInt(); textPaint.color = Color.WHITE }
                "escalator" -> { bgPaint.color = 0xDDFF9800.toInt(); textPaint.color = Color.WHITE }
                else        -> { bgPaint.color = 0xCCFFFFFF.toInt(); textPaint.color = Color.DKGRAY }
            }
            val hw = textPaint.measureText(label.text) / 2f + 14f
            canvas.drawRoundRect(
                label.sx - hw, label.sy - 22f,
                label.sx + hw, label.sy + 10f,
                10f, 10f, bgPaint
            )
            canvas.drawText(label.text, label.sx, label.sy, textPaint)
        }
    }
}
