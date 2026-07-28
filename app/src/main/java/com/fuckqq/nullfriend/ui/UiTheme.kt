package com.fuckqq.nullfriend.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity

/**
 * Ark-UI 设计系统 — Hypergryph "ark" 工业信息系统家族，应用深度 moderate。
 *
 * Stage + instrumentation: 近黑底色作舞台，青色信号作仪表。
 * 配色：ink #080a0b / paper #f4f6f6 / signal cyan #18d1ff / state green #c8eb21
 * 几何：方形为主（0-2px 圆角），1px 描边，45° 切角，强负空间
 * 字号阶梯：10 / 11 / 12 / 13 / 14 / 16 / 18 / 20 / 24 / 30
 * 间距：2 / 4 / 8 / 12 / 16 / 20 / 24 / 32
 * 圆角：0 / 2 / 4（仅功能用途）
 */
object UiTheme {

    // ---- 色板 (ark family) ----
    /** 舞台底色，近黑墨水 */
    const val INK = 0xFF080A0B.toInt()
    /** 上一级面板：墨水 +4% 提亮 */
    const val INK_2 = 0xFF101315.toInt()
    /** 面板表面：半透明黑，叠在舞台上做层次 */
    const val SURFACE = 0xFF14181A.toInt()
    const val SURFACE_HI = 0xFF1C2124.toInt()
    /** 半透明黑覆盖层 (82%) */
    const val OVERLAY = 0xD1080A0B.toInt()
    /** 纸面 / 反相前景 */
    const val PAPER = 0xFFF4F6F6.toInt()

    /** 信号青：选择 / 进度 / 主操作 */
    const val SIGNAL = 0xFF18D1FF.toInt()
    const val SIGNAL_DIM = 0xFF0E8FB5.toInt()
    const val SIGNAL_SOFT = 0x2618D1FF
    /** 状态绿：成功 / 在线 / 完成态 */
    const val STATE_OK = 0xFFC8EB21.toInt()
    /** 警告 */
    const val WARN = 0xFFFFB454.toInt()
    /** 危险 / 删除 / 未读 */
    const val DANGER = 0xFFFF5C7A.toInt()
    /** 信息蓝（次要状态） */
    const val INFO = 0xFF5B9DFF.toInt()
    /** 未读红点 */
    const val UNREAD = 0xFFFF4D5E.toInt()

    // ---- 文字 ----
    const val TEXT = 0xFFEDEFF1.toInt()
    const val TEXT_2 = 0xFF8D9396.toInt()
    const val TEXT_3 = 0xFF5A6063.toInt()
    const val TEXT_INVERSE = 0xFF080A0B.toInt()

    // ---- 描边 / 分割 ----
    const val RULE = 0xFF2A2F33.toInt()       // 1px 规线
    const val RULE_BRIGHT = 0xFF3A4248.toInt()
    const val DIVIDER = 0xFF1F2326.toInt()

    // ---- 旧别名（兼容） ----
    const val BG = INK
    const val ACCENT = SIGNAL
    const val ACCENT_PRESSED = SIGNAL_DIM
    const val ACCENT_SOFT = SIGNAL_SOFT
    const val OK = STATE_OK

    // ---- 字号 (sp) ----
    const val TEXT_MICRO = 10f
    const val TEXT_CAPTION = 11f
    const val TEXT_BODY_2 = 12f
    const val TEXT_BODY = 13f
    const val TEXT_BODY_1 = 14f
    const val TEXT_TITLE_2 = 16f
    const val TEXT_TITLE = 18f
    const val TEXT_HEADLINE = 20f
    const val TEXT_DISPLAY = 24f
    const val TEXT_DISPLAY_LG = 30f

    // ---- 间距 (dp) ----
    const val SP_0 = 2f
    const val SP_1 = 4f
    const val SP_2 = 8f
    const val SP_3 = 12f
    const val SP_4 = 16f
    const val SP_5 = 20f
    const val SP_6 = 24f
    const val SP_8 = 32f

    // ---- 圆角 (dp) —— ark 家族以方形为主 ----
    const val RADIUS_NONE = 0f
    const val RADIUS_SM = 2f
    const val RADIUS_MD = 4f
    const val RADIUS_LG = 4f
    const val RADIUS_PILL = 4f

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    /** 方形/微圆角色块，可选 1px 描边 —— 默认无圆角 */
    fun shape(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, strokeWidth.toFloat()), strokeColor)
        }

    /** 圆形 */
    fun oval(color: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 切角矩形（45° 斜切左上+右下）—— ark 标志性几何 */
    fun clipped(color: Int, ctx: Context, strokeColor: Int? = null): GradientDrawable {
        val d = shape(color, RADIUS_NONE, ctx, strokeColor)
        // 用 clip 风格：左上右下各切一个小三角
        val s = dp(ctx, 6f).toFloat()
        d.setCornerRadii(floatArrayOf(s, s, 0f, 0f, 0f, 0f, s, s))
        return d
    }

    fun ripple(normal: Int, radiusDp: Float, ctx: Context, stroke: Int? = null): android.graphics.drawable.Drawable {
        val content = shape(normal, radiusDp, ctx, stroke)
        return if (Build.VERSION.SDK_INT >= 21) {
            RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(Color.WHITE, radiusDp, ctx))
        } else {
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), shape(darken(normal), radiusDp, ctx))
                addState(intArrayOf(), content)
            }
        }
    }

    fun darken(color: Int): Int = Color.argb(
        255,
        (Color.red(color) * 0.85f).toInt(),
        (Color.green(color) * 0.85f).toInt(),
        (Color.blue(color) * 0.85f).toInt()
    )

    /** 头像背景色：按 uin hash 取色，保证同一好友颜色稳定（ark 调性，去饱和） */
    fun avatarColor(uin: String): Int {
        val palette = intArrayOf(
            0xFF6B7B85.toInt(), 0xFF8D9396.toInt(), 0xFF5B6770.toInt(), 0xFF7A8590.toInt(),
            0xFF18D1FF.toInt(), 0xFF0E8FB5.toInt(), 0xFF4A6470.toInt(), 0xFF3A4248.toInt(),
            0xFF9AA3A8.toInt(), 0xFF52606A.toInt(), 0xFF6E7C84.toInt(), 0xFF44545E.toInt()
        )
        val h = uin.hashCode().and(0x7FFFFFFF)
        return palette[h % palette.size]
    }

    fun typefaceMedium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun typefaceBold(): Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    /** 等宽 —— 数据/索引/坐标 */
    fun typefaceMono(): Typeface = Typeface.MONOSPACE
}
