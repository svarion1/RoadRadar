package com.example.roadradar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.objects.DetectedObject

/**
 * Draws per-vehicle bounding boxes and speed labels on top of the camera preview.
 *
 * Speed labels are colour-coded:
 *   Green  < 50 km/h
 *   Amber  50–79 km/h
 *   Red   ≥ 80 km/h
 *
 * Call [setDetectedObjects] from the analyzer thread whenever a new frame is
 * processed; the view redraws itself via [postInvalidate].
 */
class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var detectedObjects: List<DetectedObject> = emptyList()
    private var trackSpeeds: Map<Int, Double> = emptyMap()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    // ── Paints ──────────────────────────────────────────────────────────────

    private val boxPaintDefault = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private fun speedBoxPaint(speedKmh: Double) = Paint().apply {
        color = when {
            speedKmh < 50  -> Color.rgb(76, 175, 80)    // green
            speedKmh < 80  -> Color.rgb(255, 152, 0)    // amber
            else           -> Color.rgb(244, 67, 54)     // red
        }
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)   // semi-transparent black
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val trackIdPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 22f
        isAntiAlias = true
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Update the overlay with the latest detections and per-track speeds.
     *
     * @param objects     Raw ML Kit detected objects (used for bounding boxes & labels)
     * @param imageWidth  Camera image width in pixels
     * @param imageHeight Camera image height in pixels
     * @param speeds      Map of trackingId → speed in km/h (may be empty on first frame)
     */
    fun setDetectedObjects(
        objects: List<DetectedObject>,
        imageWidth: Int,
        imageHeight: Int,
        speeds: Map<Int, Double> = emptyMap()
    ) {
        this.detectedObjects = objects
        this.trackSpeeds = speeds
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        calculateTransformation()
        postInvalidate()
    }

    // ── Layout & drawing ─────────────────────────────────────────────────────

    private fun calculateTransformation() {
        if (width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0) {
            val scaleFactor = minOf(
                width.toFloat() / imageWidth.toFloat(),
                height.toFloat() / imageHeight.toFloat()
            )
            scaleX = scaleFactor
            scaleY = scaleFactor
            offsetX = (width  - imageWidth  * scaleFactor) / 2f
            offsetY = (height - imageHeight * scaleFactor) / 2f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateTransformation()
    }

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0) return

        detectedObjects.forEach { obj ->
            val box = obj.boundingBox
            val trackId = obj.trackingId
            val speed = trackId?.let { trackSpeeds[it] }

            // Transform coordinates: image space → view space
            val left   = box.left   * scaleX + offsetX
            val top    = box.top    * scaleY + offsetY
            val right  = box.right  * scaleX + offsetX
            val bottom = box.bottom * scaleY + offsetY
            val rect   = RectF(left, top, right, bottom)

            // Bounding box — colour-coded by speed when available
            val boxPaint = if (speed != null) speedBoxPaint(speed) else boxPaintDefault
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // Speed label
            val speedText = when {
                speed == null  -> "-- km/h"
                speed < 1.0    -> "standing"
                else           -> String.format("%.0f km/h", speed)
            }

            // Measure text to draw background pill
            val textW = labelTextPaint.measureText(speedText)
            val textH = labelTextPaint.textSize
            val padH = 6f; val padV = 4f
            val bgRect = RectF(
                left,
                top - textH - padV * 2,
                left + textW + padH * 2,
                top
            )
            // Keep label inside view bounds
            if (bgRect.top < 0) bgRect.offset(0f, -bgRect.top)

            canvas.drawRoundRect(bgRect, 6f, 6f, labelBgPaint)
            canvas.drawText(speedText, bgRect.left + padH, bgRect.bottom - padV, labelTextPaint)

            // Track ID (small, bottom-right of box)
            trackId?.let {
                canvas.drawText(
                    "#$it",
                    right - trackIdPaint.measureText("#$it") - 4f,
                    bottom - 4f,
                    trackIdPaint
                )
            }
        }
    }
}
