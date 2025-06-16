package com.example.roadradar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.objects.DetectedObject

class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var detectedObjects: List<DetectedObject> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private val boxPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setDetectedObjects(objects: List<DetectedObject>, imageWidth: Int, imageHeight: Int) {
        this.detectedObjects = objects
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        calculateTransformation()
        postInvalidate() // Request the view to redraw
    }

    private fun calculateTransformation() {
        if (width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0) {
            // Calculate scale factors
            val scaleFactorX = width.toFloat() / imageWidth.toFloat()
            val scaleFactorY = height.toFloat() / imageHeight.toFloat()
            
            // Use the smaller scale factor to maintain aspect ratio
            val scaleFactor = minOf(scaleFactorX, scaleFactorY)
            
            scaleX = scaleFactor
            scaleY = scaleFactor
            
            // Calculate offsets to center the image
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor
            
            offsetX = (width - scaledImageWidth) / 2f
            offsetY = (height - scaledImageHeight) / 2f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateTransformation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0) return

        detectedObjects.forEach { detectedObject ->
            val box = detectedObject.boundingBox
            
            // Transform coordinates from image space to view space
            val left = box.left * scaleX + offsetX
            val top = box.top * scaleY + offsetY
            val right = box.right * scaleX + offsetX
            val bottom = box.bottom * scaleY + offsetY
            
            val transformedBox = RectF(left, top, right, bottom)
            
            canvas.drawRect(transformedBox, boxPaint)

            val labelText = detectedObject.labels.firstOrNull()?.text ?: "Object"
            canvas.drawText(labelText, left, top - 10, textPaint)
        }
    }
}