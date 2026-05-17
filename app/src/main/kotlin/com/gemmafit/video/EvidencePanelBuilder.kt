package com.gemmafit.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

data class EvidencePanelTag(
    val key: String,
    val value: String,
)

object EvidencePanelBuilder {
    fun buildPanel(
        selectedFrames: SelectedEvidenceFrames,
        frameBitmaps: Map<Int, Bitmap?>,
        tags: List<EvidencePanelTag> = emptyList(),
        maxLongSide: Int = DEFAULT_LONG_SIDE,
    ): Bitmap? {
        if (maxLongSide <= 0) return null
        return runCatching {
            val longSide = maxLongSide.coerceIn(MIN_LONG_SIDE, MAX_LONG_SIDE)
            val width = longSide
            val height = (longSide * 0.75f).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(BACKGROUND)

            val margin = (width * 0.025f).coerceAtLeast(12f)
            val tagHeight = (height * 0.12f).coerceAtLeast(56f)
            val sceneRect = RectF(
                margin,
                margin,
                width - margin,
                height * 0.58f,
            )
            val stripTop = sceneRect.bottom + margin
            val stripBottom = height - margin - tagHeight
            val stripGap = margin * 0.5f
            val cellWidth = (width - margin * 2f - stripGap * 3f) / 4f
            val phaseFrames = listOf(
                "top" to selectedFrames.top,
                "descent" to selectedFrames.descent,
                "bottom" to selectedFrames.bottom,
                "ascent" to selectedFrames.ascent,
            )

            drawFrameCell(
                canvas = canvas,
                paint = paint,
                rect = sceneRect,
                bitmap = selectedFrames.sceneAnchor?.let { frameBitmaps[it.frameIndex] },
                candidate = selectedFrames.sceneAnchor,
                label = "scene",
            )

            phaseFrames.forEachIndexed { index, (label, candidate) ->
                val left = margin + index * (cellWidth + stripGap)
                val rect = RectF(left, stripTop, left + cellWidth, stripBottom)
                drawFrameCell(
                    canvas = canvas,
                    paint = paint,
                    rect = rect,
                    bitmap = candidate?.let { frameBitmaps[it.frameIndex] },
                    candidate = candidate,
                    label = label,
                )
            }

            drawHipPathProxy(canvas, paint, phaseFrames.mapNotNull { it.second }, margin, stripTop, width - margin)
            drawTags(
                canvas = canvas,
                paint = paint,
                tags = compactTags(tags, selectedFrames),
                rect = RectF(margin, stripBottom + margin * 0.5f, width - margin, height - margin),
            )
            bitmap
        }.getOrNull()
    }

    private fun drawFrameCell(
        canvas: Canvas,
        paint: Paint,
        rect: RectF,
        bitmap: Bitmap?,
        candidate: FrameEvidenceCandidate?,
        label: String,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = CELL_BACKGROUND
        canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, paint)
        val safeBitmap = bitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        if (safeBitmap != null) {
            drawBitmapInside(canvas, safeBitmap, rect)
        } else {
            paint.color = PLACEHOLDER_TEXT
            paint.textSize = max(14f, rect.height() * 0.08f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("missing", rect.centerX(), rect.centerY(), paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (candidate?.hasWarning == true) WARNING_BORDER else CELL_BORDER
        canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, paint)

        val title = if (candidate != null) "$label  #${candidate.frameIndex}" else label
        drawLabel(canvas, paint, rect, title, top = true)
        val footer = candidate?.let {
            "${it.phase.ifBlank { "unknown" }}  ${"%.2f".format(it.poseConfidence)}"
        } ?: "no frame"
        drawLabel(canvas, paint, rect, footer, top = false)
    }

    private fun drawBitmapInside(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        val source = Rect(0, 0, bitmap.width, bitmap.height)
        val scale = min(rect.width() / bitmap.width.toFloat(), rect.height() / bitmap.height.toFloat())
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = rect.left + (rect.width() - drawWidth) / 2f
        val top = rect.top + (rect.height() - drawHeight) / 2f
        val dest = RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, source, dest, null)
    }

    private fun drawLabel(
        canvas: Canvas,
        paint: Paint,
        rect: RectF,
        text: String,
        top: Boolean,
    ) {
        val labelHeight = max(24f, rect.height() * 0.12f)
        val labelRect = if (top) {
            RectF(rect.left, rect.top, rect.right, rect.top + labelHeight)
        } else {
            RectF(rect.left, rect.bottom - labelHeight, rect.right, rect.bottom)
        }
        paint.style = Paint.Style.FILL
        paint.color = LABEL_SCRIM
        canvas.drawRect(labelRect, paint)
        paint.color = Color.WHITE
        paint.textSize = max(12f, labelHeight * 0.48f)
        paint.textAlign = Paint.Align.LEFT
        val baseline = labelRect.top + labelHeight * 0.68f
        canvas.drawText(text.take(28), labelRect.left + 8f, baseline, paint)
    }

    private fun drawHipPathProxy(
        canvas: Canvas,
        paint: Paint,
        candidates: List<FrameEvidenceCandidate>,
        left: Float,
        top: Float,
        right: Float,
    ) {
        val hipValues = candidates.mapNotNull { it.hipY }
        if (hipValues.size < 2) return
        val minY = hipValues.minOrNull() ?: return
        val maxY = hipValues.maxOrNull() ?: return
        val range = (maxY - minY).takeIf { it > 0.0001f } ?: return
        val bandTop = top + 10f
        val bandHeight = 36f
        val points = candidates.mapIndexedNotNull { index, candidate ->
            val hipY = candidate.hipY ?: return@mapIndexedNotNull null
            val x = left + (right - left) * (index + 0.5f) / candidates.size
            val y = bandTop + ((hipY - minY) / range) * bandHeight
            x to y
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = HIP_PATH
        points.zipWithNext().forEach { (a, b) ->
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }
        paint.style = Paint.Style.FILL
        points.forEach { (x, y) -> canvas.drawCircle(x, y, 4f, paint) }
    }

    private fun drawTags(
        canvas: Canvas,
        paint: Paint,
        tags: List<EvidencePanelTag>,
        rect: RectF,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = TAG_BACKGROUND
        canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, paint)
        paint.color = Color.WHITE
        paint.textSize = max(13f, rect.height() * 0.28f)
        paint.textAlign = Paint.Align.LEFT
        val y = rect.centerY() + paint.textSize * 0.35f
        var x = rect.left + 12f
        tags.take(MAX_TAGS).forEach { tag ->
            val text = "${tag.key}: ${tag.value}".take(32)
            canvas.drawText(text, x, y, paint)
            x += paint.measureText(text) + 28f
            if (x > rect.right - 80f) return
        }
    }

    private fun compactTags(
        tags: List<EvidencePanelTag>,
        selectedFrames: SelectedEvidenceFrames,
    ): List<EvidencePanelTag> {
        val fallback = listOf(
            EvidencePanelTag("confidence", selectedFrames.panelConfidence),
            EvidencePanelTag("basis", selectedFrames.selectionBasis.firstOrNull().orEmpty()),
        )
        return (tags + fallback)
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_TAGS)
    }

    private const val DEFAULT_LONG_SIDE = 1024
    private const val MIN_LONG_SIDE = 320
    private const val MAX_LONG_SIDE = 1024
    private const val MAX_TAGS = 5
    private const val CORNER_RADIUS = 8f
    private val BACKGROUND = Color.rgb(18, 22, 26)
    private val CELL_BACKGROUND = Color.rgb(34, 40, 47)
    private val CELL_BORDER = Color.rgb(88, 101, 116)
    private val WARNING_BORDER = Color.rgb(255, 178, 82)
    private val PLACEHOLDER_TEXT = Color.rgb(190, 198, 207)
    private const val LABEL_SCRIM = 0x99000000.toInt()
    private val HIP_PATH = Color.rgb(85, 214, 190)
    private val TAG_BACKGROUND = Color.rgb(39, 47, 56)
}
