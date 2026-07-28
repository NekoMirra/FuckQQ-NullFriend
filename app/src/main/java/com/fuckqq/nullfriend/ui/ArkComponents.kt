package com.fuckqq.nullfriend.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * ark-ui 可复用组件集。
 *
 * - ArkProgress：方形扫描线进度指示器，替代原生 ProgressBar 旋转圆圈
 * - ArkSelector：方形选择器触发器 + PopupWindow 列表，替代原生 Spinner
 * - ArkConfirmDialog：方形确认对话框，替代原生 AlertDialog
 */

/**
 * 方形扫描线进度指示器。
 *
 * 三段信号青方块依次点亮 → 熄灭，循环。尺寸固定 20dp 方形。
 * 比 ProgressBar 旋转圆圈更契合 ark 工业仪表风。
 */
class ArkProgress @JvmOverloads constructor(
    context: Context,
    sizeDp: Float = 20f
) : View(context) {

    private val T = UiTheme
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val sizePx = T.dp(context, sizeDp)
    private val cellPx = sizePx / 3
    private var tick = 0
    @Volatile private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick = (tick + 1) % 3
            invalidate()
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 0 until 3) {
            val active = running && i == tick
            paint.color = if (active) T.SIGNAL else T.RULE_BRIGHT
            val left = i * cellPx.toFloat()
            val top = (sizePx - cellPx) / 2f
            canvas.drawRect(left, top, left + cellPx, top + cellPx, paint)
        }
    }

    fun start() {
        if (running) return
        running = true
        tick = 0
        visibility = VISIBLE
        handler.post(tickRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        visibility = if (visibility == VISIBLE) GONE else visibility
        invalidate()
    }

    companion object {
        private const val INTERVAL_MS = 160L
    }
}

/**
 * 方形选择器触发器 + PopupWindow 列表。
 *
 * 用法：构造后 addTo(parent)；options(...) 设可选项；onSelect 回调。
 * 替代原生 Spinner，外观为方形描边按钮 + 当前值 + › 箭头。
 */
class ArkSelector(
    private val activity: Activity,
    private val label: String,
    initial: String = ""
) : LinearLayout(activity) {

    private val T = UiTheme
    private val dp = { v: Float -> T.dp(activity, v) }

    private val trigger: TextView
    private var options: List<String> = emptyList()
    private var current: String = initial
    var onSelect: ((String) -> Unit)? = null
    private var popup: PopupWindow? = null

    init {
        orientation = VERTICAL
        background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
        setPadding(dp(T.SP_3), dp(T.SP_2), dp(T.SP_3), dp(T.SP_2))
        isClickable = true
        isFocusable = true

        addView(TextView(activity).apply {
            text = label
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
        })

        val row = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(T.SP_1), 0, 0)
        }
        trigger = TextView(activity).apply {
            text = if (initial.isEmpty()) "—" else initial
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            typeface = T.typefaceMedium()
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(trigger)
        row.addView(TextView(activity).apply {
            text = "›"
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_TITLE
            setPadding(dp(T.SP_2), 0, 0, 0)
        })
        addView(row)

        setOnClickListener { showPopup() }
    }

    fun options(items: List<String>, current: String? = null) {
        this.options = items
        if (current != null) {
            this.current = current
            trigger.text = current
        }
    }

    fun setCurrent(value: String) {
        current = value
        trigger.text = value
    }

    fun current(): String = current

    private fun showPopup() {
        if (options.isEmpty()) return
        val container = LinearLayout(activity).apply {
            orientation = VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
        }
        container.addView(TextView(activity).apply {
            text = label
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
            setPadding(dp(T.SP_1), 0, dp(T.SP_1), dp(T.SP_2))
        })
        options.forEach { item ->
            val row = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
                isClickable = true
                background = T.ripple(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            }
            row.addView(View(activity).apply {
                setBackgroundColor(if (item == current) T.SIGNAL else android.graphics.Color.TRANSPARENT)
            }, LayoutParams(dp(3f), dp(20f)).apply { rightMargin = dp(T.SP_3) })
            row.addView(TextView(activity).apply {
                text = item
                setTextColor(if (item == current) T.SIGNAL else T.TEXT)
                textSize = T.TEXT_BODY_1
                typeface = if (item == current) T.typefaceMedium() else android.graphics.Typeface.DEFAULT
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                current = item
                trigger.text = item
                onSelect?.invoke(item)
                popup?.dismiss()
            }
            container.addView(row, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(T.SP_1) })
        }
        val pw = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(T.shape(T.INK, 0f, activity, T.RULE))
            elevation = dp(8f).toFloat()
            isOutsideTouchable = true
            setOnDismissListener { popup = null }
        }
        popup = pw
        pw.showAsDropDown(this, 0, dp(T.SP_1))
    }

    fun dismissPopup() {
        popup?.dismiss()
    }
}

/**
 * ark 风格确认对话框。替代原生 AlertDialog。
 */
object ArkDialog {

    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String,
        onConfirm: () -> Unit
    ) {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_5), dp(T.SP_4), dp(T.SP_5), dp(T.SP_4))
        }
        container.addView(TextView(activity).apply {
            text = title
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
        })
        container.addView(TextView(activity).apply {
            text = message
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            setLineSpacing(dp(2f).toFloat(), 1.2f)
            setPadding(0, dp(T.SP_2), 0, dp(T.SP_4))
        })
        // 1px 规线
        container.addView(View(activity).apply { setBackgroundColor(T.RULE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                bottomMargin = dp(T.SP_3)
            })
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = makeBtn(activity, "取消", false, false) {}
        val confirm = makeBtn(activity, positiveLabel, true, true) {}
        btnRow.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(T.SP_2)
        })
        btnRow.addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)

        val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCancelable(true)
            window?.apply {
                setBackgroundDrawable(T.shape(T.INK, 2f, activity))
                setDimAmount(0.5f)
            }
        }
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
    }

    private fun makeBtn(ctx: Context, label: String, filled: Boolean, accent: Boolean, onClick: () -> Unit): TextView {
        val T = UiTheme
        val bg = when {
            filled && accent -> T.SIGNAL
            filled -> T.SURFACE_HI
            else -> T.SURFACE
        }
        val fg = if (filled && accent) T.INK else T.TEXT
        val stroke = if (filled && accent) null else T.RULE
        return TextView(ctx).apply {
            text = label
            setTextColor(fg)
            textSize = T.TEXT_BODY_2
            typeface = T.typefaceMedium()
            gravity = Gravity.CENTER
            minHeight = T.dp(ctx, 40f)
            setPadding(T.dp(ctx, T.SP_3), T.dp(ctx, T.SP_2), T.dp(ctx, T.SP_3), T.dp(ctx, T.SP_2))
            background = T.ripple(bg, T.RADIUS_NONE, ctx, stroke)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}
