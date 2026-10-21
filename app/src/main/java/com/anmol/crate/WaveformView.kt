package com.anmol.crate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The scrubber under the rack: a strip of bars with a fixed needle in the middle.
 * Each album owns a few bars, so dragging the strip flips through the rack.
 * While music plays, the bars near the needle breathe.
 */
class WaveformView(context: Context, private val crate: CrateView) : View(context) {

    private val density = resources.displayMetrics.density
    private val barGap = 4.2f * density
    private val barW = 1.4f * density
    private val barsPerAlbum = 3
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = barW; strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
    }

    var position = 0f
        set(value) { field = value; invalidate() }

    var playing = false
        set(value) { field = value; invalidate() }

    private var startTime = System.nanoTime()
    private var vt: VelocityTracker? = null
    private var lastX = 0f

    private fun barHeight(i: Int): Float {
        // Deterministic pseudo-random height per bar.
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return 0.18f + ((h ushr 8) and 0xff) / 255f * 0.32f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val base = height - 4f * density
        val maxH = height - 18f * density
        val t = (System.nanoTime() - startTime) / 1e9f
        val pxPerAlbum = barGap * barsPerAlbum
        val offset = position * pxPerAlbum
        val firstBar = floor((offset - cx) / barGap).toInt() - 1
        val count = (width / barGap).toInt() + 3
        for (k in 0..count) {
            val i = firstBar + k
            val x = i * barGap - offset + cx
            if (x < 0 || x > width) continue
            val dist = (x - cx) / width
            // A hump centred on the needle, fading towards the edges.
            val hump = exp(-(dist * dist) / 0.006f)
            var h = barHeight(i) * (0.35f + 0.65f * hump) + hump * 0.42f
            if (playing) h *= 0.82f + 0.18f * sin(t * 7.5f + i * 0.9f) * hump.coerceAtLeast(0.25f)
            val edge = (1f - abs(dist) * 2f).coerceIn(0f, 1f)
            barPaint.alpha = (255 * (0.28f + 0.6f * hump) * (0.4f + 0.6f * edge)).toInt()
            canvas.drawLine(x, base, x, base - maxH * h.coerceAtMost(1f) * 0.62f, barPaint)
        }
        val top = base - maxH * 0.92f
        canvas.drawLine(cx, base + 2f * density, cx, top, needlePaint)
        canvas.drawCircle(cx, top, 3.2f * density, needlePaint)
        if (playing) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pxPerAlbum = barGap * barsPerAlbum
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x
                crate.beginDrag()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                crate.dragBy(-(e.x - lastX) / pxPerAlbum)
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                crate.endDrag(-(vt?.xVelocity ?: 0f) / pxPerAlbum)
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}
