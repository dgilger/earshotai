package app.earshot

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/*
 * The look: quiet. Solid black, grey type, thin 1 px lines, one mint accent; red only while
 * recording. Ordered (Bayer) dithering is kept for one detail, the record orb. Space Mono for
 * text. Screens are built in code (no layout XML).
 */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density
fun Context.col(id: Int) = getColor(id)

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Fonts bundled in res/font (SIL Open Font License, see android/licenses). */
object Type {
    var pixel: Typeface = Typeface.MONOSPACE; private set
    var pixelBold: Typeface = Typeface.MONOSPACE; private set
    var mono: Typeface = Typeface.MONOSPACE; private set
    var monoBold: Typeface = Typeface.MONOSPACE; private set
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        try {
            pixel = ctx.resources.getFont(R.font.silkscreen_regular)
            pixelBold = ctx.resources.getFont(R.font.silkscreen_bold)
            mono = ctx.resources.getFont(R.font.spacemono_regular)
            monoBold = ctx.resources.getFont(R.font.spacemono_bold)
            loaded = true
        } catch (_: Exception) {
        }
    }
}

val MONO get() = Type.mono
val MONO_BOLD get() = Type.monoBold

object Dither {
    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21)

    /**
     * A [w]x[h] bitmap (one entry per "pixel" cell). [t] maps cell centre (0..1, 0..1) to a
     * position along the colour [stops] (0 = transparent allowed); between two stops each cell
     * takes one of the two colours by the Bayer threshold. [mask] false leaves a cell clear.
     * [phase] shifts the pattern (for shimmer).
     */
    fun bitmap(w: Int, h: Int, colors: IntArray, pos: FloatArray, phase: Int = 0,
               mask: ((Float, Float) -> Boolean)? = null, t: (Float, Float) -> Float): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) / w
            val fy = (y + 0.5f) / h
            if (mask != null && !mask(fx, fy)) continue
            val v = t(fx, fy).coerceIn(0f, 1f)
            var k = 0
            while (k < pos.size - 2 && v > pos[k + 1]) k++
            val f = (v - pos[k]) / (pos[k + 1] - pos[k]).coerceAtLeast(1e-6f)
            val th = BAYER[((y + phase) and 7) * 8 + ((x + phase * 3) and 7)] / 64f
            px[y * w + x] = if (th < f) colors[k + 1] else colors[k]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    val sharp = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
}

/** Dithered field filling its bounds. [t] gets normalised coords (and the bounds' aspect w/h). */
class DitherField(
    private val colors: IntArray, private val pos: FloatArray, private val cell: Int,
    private val t: (x: Float, y: Float, aspect: Float) -> Float,
) : Drawable() {
    private var bmp: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val w = (bounds.width() / cell).coerceAtLeast(1)
        val h = (bounds.height() / cell).coerceAtLeast(1)
        val aspect = bounds.width().toFloat() / bounds.height()
        bmp = Dither.bitmap(w, h, colors, pos) { x, y -> t(x, y, aspect) }
    }

    override fun draw(canvas: Canvas) { bmp?.let { canvas.drawBitmap(it, null, bounds, Dither.sharp) } }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Button: thin outline, or solid fill when [primary]. Slightly dimmed while pressed. */
class Notch(ctx: Context, private val color: Int, private val primary: Boolean, radius: Float = ctx.dpf(8f)) : Drawable() {
    private val r = radius
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = ctx.col(R.color.line)
    private var pressed = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val h = stroke.strokeWidth / 2
        val rect = android.graphics.RectF(bounds.left + h, bounds.top + h, bounds.right - h, bounds.bottom - h)
        if (primary) {
            fill.color = color
            fill.alpha = if (pressed) 190 else 255
            canvas.drawRoundRect(rect, r, r, fill)
        } else {
            if (pressed) { fill.color = line; canvas.drawRoundRect(rect, r, r, fill) }
            stroke.color = line
            canvas.drawRoundRect(rect, r, r, stroke)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Section surface: barely lighter than black, thin outline, rounded corners. */
class Panel(ctx: Context, @Suppress("unused") private val color: Int) : Drawable() {
    private val r = ctx.dpf(12f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ctx.col(R.color.surface) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f); this.color = ctx.col(R.color.line)
    }

    override fun draw(canvas: Canvas) {
        val h = stroke.strokeWidth / 2
        val rect = android.graphics.RectF(bounds.left + h, bounds.top + h, bounds.right - h, bounds.bottom - h)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Hairline divider. */
class Dots(ctx: Context) : Drawable() {
    private val p = Paint().apply { color = ctx.col(R.color.line) }
    override fun draw(canvas: Canvas) { canvas.drawRect(bounds, p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---- builders ----

fun LinearLayout.text(size: Float = 15f, bold: Boolean = false, color: Int = R.color.text, init: TextView.() -> Unit = {}): TextView =
    TextView(context).apply {
        textSize = size
        setTextColor(context.col(color))
        typeface = if (bold) MONO_BOLD else MONO
        setLineSpacing(0f, 1.15f)
        init()
    }.also { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }

/** Status text (time, state). */
fun LinearLayout.pixel(size: Float, color: Int = R.color.text, bold: Boolean = true, init: TextView.() -> Unit = {}): TextView =
    text(size, color = color) {
        typeface = if (bold) Type.monoBold else Type.mono
        letterSpacing = 0.04f
        includeFontPadding = false
        init()
    }

/** Section heading: small grey capitals. [color] is ignored (kept for callers). */
fun LinearLayout.label(t: String, @Suppress("UNUSED_PARAMETER") color: Int = R.color.accent): View = text(11f, color = R.color.muted) {
    text = t.uppercase()
    letterSpacing = 0.14f
    setPadding(0, 0, 0, context.dp(12))
}

fun LinearLayout.row(init: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; init() }
        .also { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }

/** HUD panel section. */
fun LinearLayout.card(color: Int = R.color.accent, init: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Panel(context, context.col(color))
        val p = context.dp(16)
        setPadding(p, p, p, p)
        init()
    }.also { addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = context.dp(16) }) }

/** Notched button with a pixel-font label. */
fun LinearLayout.pill(label: String, primary: Boolean = false, color: Int = R.color.accent, weight: Float = 0f,
                      onClick: (View) -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Type.mono
        letterSpacing = 0.02f
        stateListAnimator = null
        minHeight = context.dp(40); minimumHeight = context.dp(40)
        minWidth = 0; minimumWidth = 0
        setPadding(context.dp(16), 0, context.dp(16), 0)
        background = Notch(context, context.col(color), primary)
        setTextColor(if (primary) context.col(R.color.on_accent) else context.col(if (color == R.color.live) R.color.live else R.color.text))
        setOnClickListener(onClick)
    }.also {
        val lp = if (weight > 0) LinearLayout.LayoutParams(0, WRAP, weight) else LinearLayout.LayoutParams(WRAP, WRAP)
        lp.marginEnd = context.dp(8)
        addView(it, lp)
    }

/** Small status tag: a coloured dot and grey text, e.g. "● Saved". */
fun Context.chip(label: String, color: Int): TextView = TextView(this).apply {
    val dot = android.text.SpannableString("●  " + label.lowercase().replaceFirstChar { it.uppercase() })
    dot.setSpan(android.text.style.ForegroundColorSpan(col(color)), 0, 1, 0)
    text = dot
    textSize = 11f
    typeface = Type.mono
    setTextColor(col(R.color.muted))
}

fun LinearLayout.dots() = addView(View(context).apply { background = Dots(context) },
    LinearLayout.LayoutParams(MATCH, context.dp(1)))

fun LinearLayout.space(h: Int) = addView(View(context), LinearLayout.LayoutParams(MATCH, context.dp(h)))

/**
 * Shows [root] in a scroll view padded clear of the status bar, navigation bar and camera cutout.
 * Android 15 draws apps edge to edge, so without this the top of the screen sits under the status bar.
 */
fun Activity.showScreen(root: LinearLayout) {
    val pad = dp(16)
    val scroll = ScrollView(this).apply {
        addView(root)
        isFillViewport = true
        setBackgroundColor(col(R.color.bg))
    }
    if (Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(bars.left, 0, bars.right, bars.bottom)
        root.setPadding(pad, pad + bars.top, pad, pad)
        insets
    }
    root.setPadding(pad, pad, pad, pad)
    setContentView(scroll)
}

/** Title row: optional back button, title with a small caption, optional action; then a hairline. */
fun LinearLayout.header(title: String, caption: String, back: (() -> Unit)? = null, action: String? = null, onAction: ((View) -> Unit)? = null) {
    Type.init(context)
    row {
        setPadding(0, context.dp(8), 0, context.dp(16))
        if (back != null) pill("←") { back() }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(context).apply {
            text = title
            typeface = Type.monoBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            includeFontPadding = false
            setTextColor(context.col(R.color.text))
        })
        box.addView(TextView(context).apply {
            text = caption
            typeface = Type.mono
            textSize = 11f
            setTextColor(context.col(R.color.muted))
            setPadding(0, context.dp(4), 0, 0)
        })
        addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (action != null && onAction != null) pill(action, onClick = onAction)
    }
    addView(View(context).apply { background = Dots(context) },
        LinearLayout.LayoutParams(MATCH, context.dp(1)).apply { bottomMargin = context.dp(8) })
}
