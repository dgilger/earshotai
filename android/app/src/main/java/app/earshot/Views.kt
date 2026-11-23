package app.earshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The record control: a softly dithered orb (grey when ready, red when recording) inside a thin
 * ring of 72 ticks. Recording: the ticks light up with the mic level.
 */
class RecordDial(ctx: Context) : View(ctx) {
    var recording = false
        set(v) { if (field != v) { field = v; orb = null; invalidate() } }
    /** 0..1 mic level. */
    var level = 0f
    var speaking = false
    /** Redraw now (the level changed). */
    fun tickLevel() = invalidate()

    private val red = ctx.col(R.color.live)
    private val text = ctx.col(R.color.text)
    private val bg = ctx.col(R.color.bg)
    private val off = ctx.col(R.color.line)
    private val cell = ctx.dp(3)
    private val tick = Paint().apply { strokeCap = Paint.Cap.BUTT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var orb: Bitmap? = null
    private var shownLevel = 0f

    init {
        isClickable = true
        contentDescription = "Record"
    }

    private fun makeOrb(r: Int): Bitmap {
        val n = (2 * r / cell).coerceAtLeast(2)
        val (cs, ps) = if (recording)
            intArrayOf(0xFFFF7A70.toInt(), red, 0xFF5A1410.toInt(), bg) to floatArrayOf(0f, 0.35f, 0.8f, 1f)
        else intArrayOf(0xFFEDEDED.toInt(), 0xFF8A8F99.toInt(), 0xFF26282E.toInt(), bg) to floatArrayOf(0f, 0.4f, 0.82f, 1f)
        return Dither.bitmap(n, n, cs, ps, 0, mask = { x, y -> hypot(x - 0.5f, y - 0.5f) <= 0.5f }) { x, y ->
            hypot(x - 0.5f + 0.125f, y - 0.5f + 0.15f) / 0.625f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val size = minOf(w, context.dp(280))
        setMeasuredDimension(w, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f - context.dpf(14f)
        // Ticks.
        val ticks = 72
        shownLevel += (level - shownLevel) * 0.35f
        val lit = (shownLevel.coerceIn(0f, 1f) * ticks).roundToInt()
        for (k in 0 until ticks) {
            val a = -Math.PI / 2 + k * 2 * Math.PI / ticks
            val major = k % 6 == 0
            val r0 = outer - context.dpf(if (major) 14f else 9f)
            tick.strokeWidth = context.dpf(if (major) 2f else 1.5f)
            tick.color = if (recording && k < lit) text else off
            canvas.drawLine(cx + cos(a).toFloat() * r0, cy + sin(a).toFloat() * r0,
                cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, tick)
        }
        // Orb.
        val r = (outer * 0.72f).toInt()
        val o = orb ?: makeOrb(r).also { orb = it }
        canvas.drawBitmap(o, null, RectF(cx - r, cy - r, cx + r, cy + r), Dither.sharp)
        // Label.
        label.typeface = Type.monoBold
        label.textSize = context.dpf(22f)
        label.letterSpacing = 0.1f
        label.color = 0xFF000000.toInt()
        canvas.drawText(if (recording) "STOP" else "REC", cx, cy + context.dpf(8f), label)
        // Keep animating while on screen.
        if (recording) postInvalidateDelayed(80)
    }
}

/** Scrolling mic level history as thin grey bars. */
class Scope(ctx: Context) : View(ctx) {
    private val n = 48
    private val values = FloatArray(n)
    private var head = 0
    private val cell = ctx.dp(2)
    private val off = Paint().apply { color = ctx.col(R.color.line) }
    private val bar = Paint().apply { color = ctx.col(R.color.muted) }

    fun push(v: Float) {
        values[head] = v.coerceIn(0f, 1f)
        head = (head + 1) % n
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val bw = width / n.toFloat()
        val h = height.toFloat()
        val gap = context.dpf(2f)
        for (i in 0 until n) {
            val v = values[(head + i) % n]
            val x = i * bw
            val bh = maxOf(context.dpf(2f), v * h)
            canvas.drawRect(x, (h - bh) / 2, x + bw - gap, (h + bh) / 2, if (v > 0.02f) bar else off)
        }
    }
}
