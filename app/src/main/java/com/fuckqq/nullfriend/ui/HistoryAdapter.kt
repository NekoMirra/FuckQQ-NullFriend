package com.fuckqq.nullfriend.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuckqq.nullfriend.domain.DeletionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 被删记录列表适配器 —— ark-ui 风格。
 *
 * - 方形卡片（1px RULE 描边，无圆角）
 * - 左侧信号青竖条（未读态）/ 灰竖条（已读态）
 * - 头像首字母方形 + 数据用等宽字
 * - 点击展开操作行，长按打开聊天
 */
class HistoryAdapter(
    private val ctx: Context,
    private val onClick: (DeletionRecord) -> Unit,
    private val onLongClick: (DeletionRecord) -> Unit
) : ListAdapter<DeletionRecord, HistoryAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val timeFmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        val indicator: View
        val avatar: TextView
        val name: TextView
        val meta: TextView
        val unreadDot: View
        val actionRow: LinearLayout
        val btnProfile: TextView
        val btnChat: TextView

        init {
            val T = UiTheme
            val dp = { v: Float -> T.dp(ctx, v) }
            card.orientation = LinearLayout.VERTICAL
            card.background = T.shape(T.SURFACE, T.RADIUS_NONE, ctx, T.RULE)
            card.setPadding(0, dp(T.SP_3), 0, dp(T.SP_3))

            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 左侧状态竖条
            indicator = View(ctx)
            top.addView(indicator, LinearLayout.LayoutParams(dp(3f), dp(40f)).apply {
                rightMargin = dp(T.SP_3)
            })

            // 头像（方形）
            avatar = TextView(ctx).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = T.TEXT_TITLE_2
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            top.addView(avatar, LinearLayout.LayoutParams(dp(40f), dp(40f)))

            // 标题列
            val meta = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(T.SP_3), 0, dp(T.SP_2), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            name = TextView(ctx).apply {
                setTextColor(T.TEXT)
                textSize = T.TEXT_BODY_1
                typeface = T.typefaceMedium()
                maxLines = 1
            }
            this.meta = TextView(ctx).apply {
                setTextColor(T.TEXT_2)
                textSize = T.TEXT_BODY_2
                typeface = T.typefaceMono()
                setPadding(0, dp(2f), 0, 0)
            }
            meta.addView(name)
            meta.addView(this.meta)
            top.addView(meta)

            // 未读角标
            unreadDot = View(ctx).apply {
                background = T.oval(T.UNREAD, ctx)
                visibility = View.GONE
            }
            top.addView(unreadDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply {
                rightMargin = dp(T.SP_3)
            })

            // 箭头
            val arrow = TextView(ctx).apply {
                text = "›"
                setTextColor(T.TEXT_3)
                textSize = T.TEXT_TITLE
                gravity = Gravity.CENTER
            }
            top.addView(arrow)

            card.addView(top)

            // 操作行
            actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(dp(T.SP_4), dp(T.SP_2), dp(T.SP_4), 0)
            }
            btnProfile = makeBtn("资料卡", false)
            btnChat = makeBtn("发消息", true)
            actionRow.addView(btnProfile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(T.SP_2)
            })
            actionRow.addView(btnChat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(actionRow)
        }

        private fun makeBtn(label: String, accent: Boolean): TextView = TextView(ctx).apply {
            val U = UiTheme
            text = label
            setTextColor(if (accent) U.INK else U.TEXT)
            textSize = U.TEXT_BODY_2
            typeface = U.typefaceMedium()
            gravity = Gravity.CENTER
            minHeight = U.dp(ctx, 40f)
            setPadding(U.dp(ctx, U.SP_2), U.dp(ctx, 10f), U.dp(ctx, U.SP_2), U.dp(ctx, 10f))
            background = U.ripple(if (accent) U.SIGNAL else U.SURFACE_HI, U.RADIUS_NONE, ctx, if (!accent) U.RULE else null)
            isClickable = true
            isFocusable = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LinearLayout(ctx)
        card.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = getItem(position)
        val T = UiTheme
        val ch = rec.friendName.trim().firstOrNull()?.toString() ?: "#"
        holder.avatar.text = ch
        holder.avatar.background = T.shape(
            if (rec.read) 0xFF2A2F33.toInt() else T.avatarColor(rec.friendUin), T.RADIUS_NONE, ctx
        )
        holder.indicator.setBackgroundColor(if (rec.read) T.RULE_BRIGHT else T.SIGNAL)
        holder.name.text = rec.friendName.ifBlank { rec.friendUin }
        holder.meta.text = "${rec.friendUin}  ·  ${timeFmt.format(Date(rec.detectedAt))}"
        holder.unreadDot.visibility = if (rec.read) View.GONE else View.VISIBLE

        holder.actionRow.visibility = View.GONE
        holder.card.setOnClickListener {
            val show = holder.actionRow.visibility != View.VISIBLE
            holder.actionRow.visibility = if (show) View.VISIBLE else View.GONE
            if (show) onClick(rec)
        }
        holder.card.setOnLongClickListener {
            onLongClick(rec)
            true
        }
        holder.btnProfile.setOnClickListener { onClick(rec) }
        holder.btnChat.setOnClickListener { onLongClick(rec) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DeletionRecord>() {
            override fun areItemsTheSame(a: DeletionRecord, b: DeletionRecord) = a.id == b.id
            override fun areContentsTheSame(a: DeletionRecord, b: DeletionRecord) =
                a.id == b.id && a.read == b.read && a.detectedAt == b.detectedAt
        }
    }
}
