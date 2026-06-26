package com.example.acousticlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max

class FrequencySpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF6200EE.toInt())
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        textSize = resources.displayMetrics.density * 10f
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelPaint.color
        textSize = resources.displayMetrics.density * 12f
        textAlign = Paint.Align.LEFT
    }

    private var bandValues = FloatArray(ScanConfig.SPECTRUM_BANDS_HZ.size)
    private val barRect = RectF()

    fun setBandValues(values: FloatArray) {
        bandValues = values.copyOf(ScanConfig.SPECTRUM_BANDS_HZ.size.coerceAtMost(values.size))
        if (bandValues.size < ScanConfig.SPECTRUM_BANDS_HZ.size) {
            bandValues = bandValues.copyOf(ScanConfig.SPECTRUM_BANDS_HZ.size)
        }
        invalidate()
    }

    fun reset() {
        bandValues = FloatArray(ScanConfig.SPECTRUM_BANDS_HZ.size)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val leftPad = paddingLeft.toFloat()
        val topPad = paddingTop.toFloat()
        val chartLeft = leftPad
        val chartTop = topPad + titlePaint.textSize + 8f
        val chartRight = width - paddingRight.toFloat()
        val chartBottom = height - paddingBottom.toFloat() - labelPaint.textSize - 8f
        val chartWidth = max(1f, chartRight - chartLeft)
        val chartHeight = max(1f, chartBottom - chartTop)

        canvas.drawText(context.getString(R.string.spectrum_title), chartLeft, topPad + titlePaint.textSize, titlePaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, gridPaint)

        val barCount = ScanConfig.SPECTRUM_BANDS_HZ.size
        val gap = chartWidth * 0.02f
        val barWidth = (chartWidth - gap * (barCount + 1)) / barCount

        for (index in 0 until barCount) {
            val value = bandValues.getOrElse(index) { 0f }
            val left = chartLeft + gap + index * (barWidth + gap)
            val barHeight = value * chartHeight
            barRect.set(left, chartBottom - barHeight, left + barWidth, chartBottom)
            canvas.drawRoundRect(barRect, 6f, 6f, barPaint)

            val label = formatBandLabel(ScanConfig.SPECTRUM_BANDS_HZ[index])
            canvas.drawText(label, left + barWidth / 2f, chartBottom + labelPaint.textSize + 4f, labelPaint)
        }
    }

    private fun formatBandLabel(hz: Int): String {
        return if (hz >= 1000) "${hz / 1000}k" else hz.toString()
    }
}
