package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * One icon: a pixel sprite, or a 2x2 preview for a folder, with an optional pixel-font label.
 * Drawn by hand, no child views, so a page of 42 of these is cheap.
 */
class AppIconView(context: Context) : View(context) {
    /** What this icon stands for; the launcher reads it back on tap and drag. */
    var tagItem: Any? = null

    var sprite: Bitmap? = null
    var folderSprites: List<Bitmap?>? = null

    /** A folder showing one app as its cover (drawn big inside the frame). */
    var folderCover: Bitmap? = null

    /** A folder's chosen colour, or null for the plain frame. */
    var folderTint: Int? = null

    /** A folder's look: "grid" (the 2x2 frame), or one of FolderArt.STYLES. */
    var folderStyle = "grid"
        set(v) { field = v; updateChannelSurfing() }

    /** Apps in the folder, shown as a small number when [showCount] is on. */
    var folderCount = 0
    var showCount = false

    private var channel = 0
    private var lid = 0f
    private val surf = object : Runnable {
        override fun run() {
            channel++
            invalidate()
            postDelayed(this, 2500)
        }
    }

    /** The TV flips through the apps inside, but only while it's on screen. */
    private fun updateChannelSurfing() {
        removeCallbacks(surf)
        if (folderStyle == "tv" && isAttachedToWindow && windowVisibility == VISIBLE && Pixel.motion(context)) postDelayed(surf, 2500)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateChannelSurfing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(surf)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateChannelSurfing()
    }

    /**
     * A folder opening: the chest's lid flips up first (a moment of fun, under a fifth of a
     * second); everything else opens straight away.
     */
    fun playOpen(then: () -> Unit) {
        if (folderStyle != "chest" || !Pixel.motion(context)) return then()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
            interpolator = Ease.enter
            addUpdateListener { lid = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    then()
                    postDelayed({ lid = 0f; invalidate() }, 300)
                }
            })
            start()
        }
    }
    var label: String = ""
    var showLabel = true
    var iconSizeDp = 50
    var accent = Pixel.ACCENTS[0]

    /** A notification dot: something new in this app (or in this folder). */
    var hasDot = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Called when, after a long press, the finger moves: time to start dragging. */
    var onDragOut: ((AppIconView) -> Unit)? = null

    /**
     * Inside an open folder: return true to keep this drag in the folder. The icon then passes
     * every finger position (screen coordinates) to [onHoldMove] until the finger lifts.
     */
    var onHoldDrag: ((AppIconView) -> Boolean)? = null
    var onHoldMove: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onHoldEnd: ((rawX: Float, rawY: Float, cancelled: Boolean) -> Unit)? = null
    private var holding = false

    /** Picked up: draw a hard pixel shadow under it, as if it's floating. */
    var lifted = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val liftPaint = Paint().apply {
        isFilterBitmap = false
        colorFilter = android.graphics.PorterDuffColorFilter(withAlpha(Pixel.SHADOW, 150), android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Pixel.font(context)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        color = Pixel.INK
    }
    private val shadowPaint = Paint(textPaint).apply { color = Pixel.SHADOW }
    private val placeholderPaint = Paint().apply { color = withAlpha(Pixel.MUTED, 90) }
    private val dotPaint = Paint()
    private val folder = PanelDrawable(context, withAlpha(Pixel.PANEL, 230), Pixel.MUTED, shadow = false)
    private val dst = RectF()
    private val src = Rect()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val px = Pixel.dp(context, 1f)

    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    /** Icon edge in pixels: a whole multiple of the 32px sprite so every pixel is the same size. */
    fun iconPx(): Int {
        val want = Pixel.dp(context, iconSizeDp.toFloat())
        val labelSpace = if (showLabel) textPaint.textSize * 2f else 0f
        val room = minOf(width.toFloat(), height - labelSpace).takeIf { it > 0 } ?: want
        val target = minOf(want, room * 0.92f)
        val scale = max(1, (target / IconCache.SIZE).toInt())
        return scale * IconCache.SIZE
    }

    /** Where the icon square sits inside this view (for launch animations and drag shadows). */
    fun iconRect(): Rect {
        val size = iconPx()
        val labelSpace = if (showLabel) (textPaint.textSize * 1.6f).toInt() else 0
        val left = (width - size) / 2
        val top = ((height - size - labelSpace) / 2).coerceAtLeast(0)
        return Rect(left, top, left + size, top + size)
    }

    private var wasPressed = false

    /** Pressed: the icon squishes in a touch; released, it springs back. */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed == wasPressed) return
        wasPressed = isPressed
        if (!Pixel.motion(context)) return
        val r = iconRect()
        pivotX = r.exactCenterX()
        pivotY = r.exactCenterY()
        if (isPressed) {
            animate().scaleX(0.9f).scaleY(0.9f).setDuration(110).setInterpolator(Ease.move).start()
        } else {
            animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = iconRect()
        dst.set(r)
        val folderApps = folderSprites
        if (folderApps != null && folderStyle != "grid") {
            val look = FolderArt.Look(folderStyle, folderApps, folderCover, folderTint, channel, lid)
            if (FolderArt.draw(canvas, r, look)) {
                drawExtras(canvas, r)
                return
            }
        }
        if (folderApps != null) {
            val tint = folderTint
            folder.fill = if (tint != null) withAlpha(blend(Pixel.PANEL, tint, 0.3f), 235) else withAlpha(Pixel.PANEL, 230)
            folder.border = tint ?: Pixel.MUTED
            folder.setBounds(r)
            folder.draw(canvas)
            // 2x2 mini sprites, each a whole number of pixels.
            val inner = r.width() - 6 * px.toInt()
            val cell = max(IconCache.SIZE / 2, (inner / 2 / (IconCache.SIZE / 2)) * (IconCache.SIZE / 2))
            val gap = (r.width() - cell * 2) / 3
            folderApps.take(4).forEachIndexed { i, bmp ->
                val l = r.left + gap + (i % 2) * (cell + gap)
                val t = r.top + gap + (i / 2) * (cell + gap)
                dst.set(l.toFloat(), t.toFloat(), (l + cell).toFloat(), (t + cell).toFloat())
                if (bmp != null) canvas.drawBitmap(bmp, null, dst, spritePaint) else canvas.drawRect(dst, placeholderPaint)
            }
        } else {
            val bmp = sprite
            if (bmp != null) {
                src.set(0, 0, bmp.width, bmp.height)
                if (lifted) {
                    // The sprite's own silhouette, three sprite-pixels down and right.
                    val off = r.width() / IconCache.SIZE * 3f
                    dst.offset(off, off)
                    canvas.drawBitmap(bmp, src, dst, liftPaint)
                    dst.offset(-off, -off)
                }
                canvas.drawBitmap(bmp, src, dst, spritePaint)
            } else {
                // Sprite still being made: a quiet block, never a fake icon.
                val inset = r.width() / 8f
                canvas.drawRect(dst.left + inset, dst.top + inset, dst.right - inset, dst.bottom - inset, placeholderPaint)
            }
        }
        drawExtras(canvas, r)
    }

    /** The notification dot and the label: the same for apps and folders. */
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Pixel.font(context)
        color = Pixel.INK
    }

    private fun drawExtras(canvas: Canvas, r: Rect) {
        if (showCount && folderCount > 0) {
            // A little numbered tag on the bottom-left corner.
            val p = r.width() / IconCache.SIZE.toFloat()
            val text = if (folderCount > 99) "99" else folderCount.toString()
            countPaint.textSize = p * 6
            val w = countPaint.measureText(text) + p * 3
            val h = p * 8
            val l = r.left.toFloat()
            val b = r.bottom.toFloat()
            dotPaint.color = Pixel.SHADOW
            canvas.drawRect(l, b - h, l + w, b, dotPaint)
            dotPaint.color = accent
            canvas.drawRect(l + p, b - h + p, l + w - p, b - p, dotPaint)
            countPaint.color = Pixel.BG
            canvas.drawText(text, l + p * 1.5f, b - p * 2f, countPaint)
        }
        if (hasDot) {
            // A 3x3 sprite-pixel square on the top-right corner, outlined so it reads on any icon.
            val p = r.width() / IconCache.SIZE.toFloat()
            val size = p * 5
            val right = r.right.toFloat()
            val top = r.top.toFloat()
            dotPaint.color = Pixel.SHADOW
            canvas.drawRect(right - size, top, right, top + size, dotPaint)
            dotPaint.color = accent
            canvas.drawRect(right - size + p, top + p, right - p, top + size - p, dotPaint)
        }
        if (showLabel && label.isNotEmpty()) {
            val text = fit(label, width - 4 * px)
            val x = (width - textPaint.measureText(text)) / 2
            val y = r.bottom + textPaint.textSize * 1.35f
            canvas.drawText(text, x + px, y + px, shadowPaint)
            canvas.drawText(text, x, y, textPaint)
        }
    }

    private fun fit(text: String, room: Float): String {
        if (textPaint.measureText(text) <= room) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text, 0, end) + textPaint.measureText("..") > room) end--
        return text.substring(0, end).trimEnd() + ".."
    }

    override fun performLongClick(): Boolean {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return super.performLongClick()
    }

    /** The folder (or anything holding this drag) let go of it, e.g. to hand it to the home screen. */
    fun stopHolding() {
        holding = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (holding) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onHoldMove?.invoke(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holding = false
                    isPressed = false
                    onHoldEnd?.invoke(event.rawX, event.rawY, event.actionMasked == MotionEvent.ACTION_CANCEL)
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressed = false
            }
            MotionEvent.ACTION_MOVE -> if (longPressed && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                longPressed = false
                cancelLongPress()
                if (onHoldDrag?.invoke(this) == true) {
                    holding = true
                    isPressed = false
                    onHoldMove?.invoke(event.rawX, event.rawY)
                } else {
                    onDragOut?.invoke(this)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressed) {
                    // The menu is showing; a release shouldn't also count as a tap.
                    longPressed = false
                    isPressed = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** A drag shadow that is just the sprite, the same size as on screen. */
    fun shadowBuilder(): DragShadowBuilder = object : DragShadowBuilder(this) {
        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            val s = iconPx()
            size.set(s, s)
            touch.set(s / 2, s / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val s = iconPx()
            val r = Rect(0, 0, s, s)
            val folderApps = folderSprites
            if (folderApps != null) {
                folder.setBounds(r)
                folder.draw(canvas)
            } else {
                sprite?.let { canvas.drawBitmap(it, null, r, spritePaint) }
            }
        }
    }
}
