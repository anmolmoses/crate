package com.anmol.crate

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A rack of CD jewel cases rendered in 3D on a plain Canvas. Each case is a box whose
 * faces are projected with a weak-perspective camera and drawn with poly-to-poly matrices,
 * back to front. The case at [position] is pulled out of the rack toward the viewer.
 */
class CrateView(context: Context) : View(context) {

    interface Listener {
        fun onCenterChanged(index: Int, fromUser: Boolean)
        fun onSettled(index: Int)
        fun onSelectedTapped()
    }

    var listener: Listener? = null
    var albums: List<Album> = emptyList()
        set(value) { field = value; invalidate() }

    /** Continuous index of the album pulled out of the rack. */
    var position = 0f
        private set

    var onPositionChanged: ((Float) -> Unit)? = null

    // Case geometry in world units (a jewel case is a little wider than tall).
    private val caseW = 1.14f
    private val caseH = 1.0f
    private val caseD = 0.12f
    private val spacing = 0.40f
    private val gap = 0.08f
    private val popOut = 1.22f
    private val popForward = -0.05f
    private val rowYaw = Math.toRadians(0.0).toFloat()
    private val popYaw = Math.toRadians(-6.0).toFloat()

    // Camera: looking down onto the rack from the front-right.
    private val camYaw = Math.toRadians(-28.0).toFloat()
    private val camPitch = Math.toRadians(-21.0).toFloat()
    private val focal = 10f
    private val cy0 = cos(camYaw); private val sy0 = sin(camYaw)
    private val cp0 = cos(camPitch); private val sp0 = sin(camPitch)

    private var scale = 1f
    private var anchorX = 0f
    private var anchorY = 0f
    private val target = floatArrayOf(1.22f, 0.5f, 0f)

    private var intro = 1f
    private var animator: ValueAnimator? = null
    private var lastSlot = Int.MIN_VALUE
    private var fromUser = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val m = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val tmp = FloatArray(3)
    private val insertRect = RectF(0f, 0f, Catalog.INSERT_W.toFloat(), Catalog.INSERT_H.toFloat())

    private class Inst(var index: Int = 0, var d: Float = 0f, var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
                       var yaw: Float = 0f, var depth: Float = 0f, var pop: Float = 0f, var alpha: Float = 1f,
                       val corners: FloatArray = FloatArray(16), var frontVisible: Boolean = false)

    private val pool = ArrayList<Inst>()
    private val drawList = ArrayList<Inst>()

    init {
        glossPaint.shader = LinearGradient(
            0f, 0f, Catalog.INSERT_W * 0.7f, Catalog.INSERT_H.toFloat(),
            intArrayOf(0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x14FFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        shadowPaint.shader = RadialGradient(
            0.5f, 0.5f, 0.5f, intArrayOf(0x8C000000.toInt(), 0x38000000, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = w * 0.37f
        anchorX = w * 0.72f
        anchorY = h * 0.55f
    }

    // ---- Projection -------------------------------------------------------------------

    /** World -> view space (x right, y up, z into the screen), relative to the camera target. */
    private fun view(wx: Float, wy: Float, wz: Float, out: FloatArray) {
        val x = wx - target[0]; val y = wy - target[1]; val z = wz - target[2]
        val x1 = x * cy0 - z * sy0
        val z1 = x * sy0 + z * cy0
        val y2 = y * cp0 - z1 * sp0
        val z2 = y * sp0 + z1 * cp0
        out[0] = x1; out[1] = y2; out[2] = z2
    }

    private fun project(wx: Float, wy: Float, wz: Float, out: FloatArray, o: Int) {
        view(wx, wy, wz, tmp)
        val k = focal / (focal + tmp[2])
        out[o] = anchorX + tmp[0] * k * scale
        out[o + 1] = anchorY - tmp[1] * k * scale
    }

    /** Screen-space vector covered by moving one album along the rack. */
    private fun stepVector(): Pair<Float, Float> {
        val a = FloatArray(2); val b = FloatArray(2)
        project(0f, 0f, 0f, a, 0); project(0f, 0f, spacing, b, 0)
        return (b[0] - a[0]) to (b[1] - a[1])
    }

    // ---- Layout -----------------------------------------------------------------------

    private fun layoutCases() {
        drawList.clear()
        val n = albums.size
        if (n == 0) return
        val c = position
        // The rack loops, so slots outside 0 until n wrap around to the start again.
        val first = floor(c).toInt() - 6
        val last = floor(c).toInt() + 16
        var p = 0
        for (i in first..last) {
            val inst = if (p < pool.size) pool[p] else Inst().also { pool += it }
            p++
            val d = i - c
            val pop = smooth(1f - abs(d).coerceAtMost(1f))
            inst.index = i
            inst.d = d
            inst.pop = pop
            inst.x = popOut * pop
            inst.z = d * spacing + gap * tanh(1.7f * d) + popForward * pop
            inst.yaw = rowYaw + (popYaw - rowYaw) * pop

            // Intro: cases rise up into place, front of the rack first.
            val order = ((d + 6f) / 22f).coerceIn(0f, 1f)
            val t = ((intro - order * 0.45f) / 0.55f).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            inst.y = -(1f - e) * 3.2f
            inst.alpha = t.coerceAtMost(1f)

            computeCorners(inst)
            view(inst.x, inst.y + caseH / 2, inst.z, tmp)
            inst.depth = tmp[2]
            drawList += inst
        }
        drawList.sortByDescending { it.depth }
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Eight box corners in world space, packed as 8 * (x, y) after projection into [Inst.corners]. */
    private fun computeCorners(inst: Inst) {
        val cs = cos(inst.yaw); val sn = sin(inst.yaw)
        val hw = caseW / 2; val hd = caseD / 2
        var k = 0
        for (ci in 0 until 8) {
            val lx = if (ci and 1 == 0) -hw else hw
            val ly = if (ci and 2 == 0) 0f else caseH
            val lz = if (ci and 4 == 0) -hd else hd
            val wx = lx * cs + lz * sn + inst.x
            val wz = -lx * sn + lz * cs + inst.z
            project(wx, ly + inst.y, wz, inst.corners, k)
            k += 2
        }
    }

    // Corner ids: bit0 = +x, bit1 = top, bit2 = back. Faces listed TL, TR, BR, BL as seen from outside.
    private val faceFront = intArrayOf(2, 3, 1, 0)
    private val faceRight = intArrayOf(3, 7, 5, 1)
    private val faceLeft = intArrayOf(6, 2, 0, 4)
    private val faceTop = intArrayOf(6, 7, 3, 2)

    private fun loadFace(inst: Inst, face: IntArray): Boolean {
        for (j in 0 until 4) {
            dst[j * 2] = inst.corners[face[j] * 2]
            dst[j * 2 + 1] = inst.corners[face[j] * 2 + 1]
        }
        var area = 0f
        for (j in 0 until 4) {
            val n = (j + 1) % 4
            area += dst[j * 2] * dst[n * 2 + 1] - dst[n * 2] * dst[j * 2 + 1]
        }
        return area > 0f
    }

    private fun facePath() {
        path.reset()
        path.moveTo(dst[0], dst[1])
        for (j in 1 until 4) path.lineTo(dst[j * 2], dst[j * 2 + 1])
        path.close()
    }

    // ---- Drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        layoutCases()
        if (drawList.isEmpty()) return

        for (inst in drawList) drawShadow(canvas, inst)
        for (inst in drawList) drawCase(canvas, inst)
    }

    private fun drawShadow(canvas: Canvas, inst: Inst) {
        if (inst.alpha <= 0.01f) return
        // A soft blob on the floor under the case, offset away from the light.
        val cs = cos(inst.yaw); val sn = sin(inst.yaw)
        val hw = caseW * 0.62f; val hd = caseD * 0.5f + 0.16f + 0.18f * inst.pop
        val pts = floatArrayOf(-hw, hd + 0.1f, hw, hd + 0.1f, hw, -hd + 0.1f, -hw, -hd + 0.1f)
        for (j in 0 until 4) {
            val lx = pts[j * 2]; val lz = pts[j * 2 + 1]
            project(lx * cs + lz * sn + inst.x + 0.05f, 0f, -lx * sn + lz * cs + inst.z, dst, j * 2)
        }
        src[0] = 0f; src[1] = 0f; src[2] = 1f; src[3] = 0f; src[4] = 1f; src[5] = 1f; src[6] = 0f; src[7] = 1f
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return
        canvas.save()
        canvas.concat(m)
        shadowPaint.alpha = (255 * inst.alpha * (0.55f + 0.45f * inst.pop)).toInt()
        canvas.drawRect(0f, 0f, 1f, 1f, shadowPaint)
        canvas.restore()
    }

    private fun drawCase(canvas: Canvas, inst: Inst) {
        if (inst.alpha <= 0.01f) return
        val album = albumAt(inst.index)
        val a = inst.alpha
        // Cases further back in the rack sit in shadow.
        val shade = ((0.18f + 0.03f * abs(inst.d)) * (1f - inst.pop)).coerceAtMost(0.55f)

        if (loadFace(inst, faceLeft)) drawEdge(canvas, 0xFFC4CBD2.toInt(), shade * 0.5f + 0.08f, a)
        if (loadFace(inst, faceRight)) drawEdge(canvas, 0xFFE8ECF0.toInt(), shade * 0.4f, a, ribbed = true)
        if (loadFace(inst, faceTop)) drawEdge(canvas, 0xFFF6F8FA.toInt(), shade * 0.25f, a)

        inst.frontVisible = loadFace(inst, faceFront)
        if (inst.frontVisible) {
            val w = insertRect.width(); val h = insertRect.height()
            src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f; src[4] = w; src[5] = h; src[6] = 0f; src[7] = h
            if (m.setPolyToPoly(src, 0, dst, 0, 4)) {
                canvas.save()
                canvas.concat(m)
                val alpha = (255 * a).toInt()
                // Clear plastic frame around the insert.
                fill.color = 0xFFD5DBE1.toInt(); fill.alpha = (alpha * 0.9f).toInt()
                canvas.drawRect(insertRect, fill)
                val inset = w * 0.028f
                val ins = album.insert
                if (ins != null) {
                    bmpPaint.alpha = alpha
                    canvas.drawBitmap(ins, null, RectF(inset, inset, w - inset, h - inset), bmpPaint)
                } else {
                    fill.color = album.tint; fill.alpha = alpha
                    canvas.drawRect(inset, inset, w - inset, h - inset, fill)
                }
                // Hinge strip on the left of the lid.
                fill.color = 0x30FFFFFF
                canvas.drawRect(0f, 0f, inset * 1.6f, h, fill)
                glossPaint.alpha = alpha
                canvas.drawRect(insertRect, glossPaint)
                if (shade > 0f) {
                    shadePaint.color = Color.argb((255 * shade * a).toInt(), 0, 0, 0)
                    canvas.drawRect(insertRect, shadePaint)
                }
                stroke.color = Color.argb((210 * a).toInt(), 255, 255, 255)
                stroke.strokeWidth = 2.2f
                canvas.drawRect(1f, 1f, w - 1f, h - 1f, stroke)
                stroke.color = Color.argb((70 * a).toInt(), 0, 0, 0)
                stroke.strokeWidth = 1.2f
                canvas.drawRect(inset, inset, w - inset, h - inset, stroke)
                canvas.restore()
            }
        }
    }

    private fun drawEdge(canvas: Canvas, color: Int, shade: Float, a: Float, ribbed: Boolean = false) {
        facePath()
        val r = (Color.red(color) * (1f - shade)).toInt()
        val g = (Color.green(color) * (1f - shade)).toInt()
        val b = (Color.blue(color) * (1f - shade)).toInt()
        fill.color = Color.argb((235 * a).toInt(), r, g, b)
        canvas.drawPath(path, fill)
        stroke.color = Color.argb((120 * a).toInt(), 255, 255, 255)
        stroke.strokeWidth = 1.2f
        canvas.drawPath(path, stroke)
        if (ribbed) {
            // A darker seam down the middle of the case thickness.
            val mx0 = (dst[0] + dst[2]) / 2; val my0 = (dst[1] + dst[3]) / 2
            val mx1 = (dst[6] + dst[4]) / 2; val my1 = (dst[7] + dst[5]) / 2
            stroke.color = Color.argb((60 * a).toInt(), 0, 0, 0)
            canvas.drawLine(mx0, my0, mx1, my1, stroke)
        }
    }

    // ---- Motion -----------------------------------------------------------------------

    fun playIntro() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { intro = it.animatedValue as Float; invalidate() }
            start()
        }
        intro = 0f
    }

    fun setPositionImmediate(p: Float) {
        animator?.cancel()
        updatePosition(p)
    }

    private fun wrap(slot: Int) = Math.floorMod(slot, albums.size.coerceAtLeast(1))

    private fun albumAt(slot: Int) = albums[wrap(slot)]

    private fun updatePosition(p: Float) {
        position = p
        val slot = position.roundToInt()
        if (slot != lastSlot) {
            if (lastSlot != Int.MIN_VALUE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastSlot = slot
            listener?.onCenterChanged(wrap(slot), fromUser)
        }
        onPositionChanged?.invoke(position)
        invalidate()
    }

    /** Album index currently pulled out of the rack. */
    val centerIndex get() = wrap(position.roundToInt())

    /** Animates to [index] the short way round the loop. */
    fun animateTo(index: Int, durationMs: Long, interp: TimeInterpolator = DecelerateInterpolator(1.8f), user: Boolean = true) {
        val n = albums.size
        val delta = Math.floorMod(index - centerIndex + n / 2, n) - n / 2
        animateToSlot(position.roundToInt() + delta, durationMs, interp, user)
    }

    /** Spins forward through [steps] albums. */
    fun spinBy(steps: Int, durationMs: Long, interp: TimeInterpolator) =
        animateToSlot(position.roundToInt() + steps, durationMs, interp, user = false)

    private fun animateToSlot(slot: Int, durationMs: Long, interp: TimeInterpolator, user: Boolean = true) {
        animator?.cancel()
        val target = slot.toFloat()
        val start = position
        fromUser = user
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener { updatePosition(start + (target - start) * it.animatedFraction) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) listener?.onSettled(wrap(slot))
                }
            })
            start()
        }
    }

    fun beginDrag() {
        animator?.cancel()
        fromUser = true
    }

    /** Moves the rack by [delta] albums. */
    fun dragBy(delta: Float) = updatePosition(position + delta)

    /** Ends a drag with [velocity] in albums per second and glides to a resting album. */
    fun endDrag(velocity: Float) {
        val projected = position + velocity * 0.22f
        val target = projected.roundToInt()
        val dist = abs(target - position)
        animateToSlot(target, (260 + 70 * dist).toLong().coerceAtMost(1400), DecelerateInterpolator(1.8f))
    }

    // ---- Touch ------------------------------------------------------------------------

    private var velocityTracker: android.view.VelocityTracker? = null
    private var lastX = 0f
    private var lastY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = hitTest(e.x, e.y) ?: return false
            if (hit == position.roundToInt()) listener?.onSelectedTapped()
            else animateToSlot(hit, (300 + 60 * abs(hit - position)).toLong().coerceAtMost(1100), DecelerateInterpolator(1.8f))
            return true
        }
    })

    private fun pixelsToAlbums(dx: Float, dy: Float): Float {
        val (vx, vy) = stepVector()
        val len2 = vx * vx + vy * vy
        // Horizontal drags are what people do most, so weight them like the waveform.
        return -((dx * vx + dy * vy) / len2) * 0.55f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(e)
                val dx = e.x - lastX; val dy = e.y - lastY
                if (!dragging && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) {
                    dragging = true
                    beginDrag()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) dragBy(pixelsToAlbums(dx, dy))
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val vt = velocityTracker!!
                    vt.addMovement(e)
                    vt.computeCurrentVelocity(1000)
                    endDrag(pixelsToAlbums(vt.xVelocity, vt.yVelocity))
                }
                dragging = false
                velocityTracker?.recycle(); velocityTracker = null
            }
        }
        return true
    }

    private var dragging = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** Topmost case whose front face or edges contain the point. */
    private fun hitTest(x: Float, y: Float): Int? {
        for (k in drawList.indices.reversed()) {
            val inst = drawList[k]
            for (face in arrayOf(faceFront, faceRight, faceTop)) {
                loadFace(inst, face)
                if (inside(x, y)) return inst.index
            }
        }
        return null
    }

    private fun inside(x: Float, y: Float): Boolean {
        var sign = 0
        for (j in 0 until 4) {
            val n = (j + 1) % 4
            val cross = (dst[n * 2] - dst[j * 2]) * (y - dst[j * 2 + 1]) - (dst[n * 2 + 1] - dst[j * 2 + 1]) * (x - dst[j * 2])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return true
    }
}
