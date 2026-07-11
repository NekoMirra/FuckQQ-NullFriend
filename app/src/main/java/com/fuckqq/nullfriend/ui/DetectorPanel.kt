package com.fuckqq.nullfriend.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fuckqq.nullfriend.BuildConfig
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.domain.DetectionOutcome
import com.fuckqq.nullfriend.domain.DeletionRecord
import com.fuckqq.nullfriend.service.ChatLauncher
import com.fuckqq.nullfriend.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process polished panel UI (no module resources required).
 */
object DetectorPanel {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val intervalLabels = listOf("关闭", "30 分钟", "1 小时", "3 小时")
    private val intervalValues = listOf(0, 30, 60, 180)

    // Palette
    private const val C_BG = 0xFFF5F6F8.toInt()
    private const val C_CARD = 0xFFFFFFFF.toInt()
    private const val C_PRIMARY = 0xFFFF5722.toInt()
    private const val C_PRIMARY_DARK = 0xFFE64A19.toInt()
    private const val C_TEXT = 0xFF1A1A1A.toInt()
    private const val C_SUB = 0xFF8A8F98.toInt()
    private const val C_LINE = 0xFFEEF0F3.toInt()
    private const val C_CHIP_BG = 0xFFFFF3EE.toInt()
    private const val C_OK = 0xFF2E7D32.toInt()
    private const val C_WARN = 0xFFC62828.toInt()
    private const val C_BTN_SECONDARY = 0xFFF0F1F3.toInt()

    @Volatile
    private var openDialog: Dialog? = null

    fun show(activity: Activity) {
        mainHandler.post {
            try {
                if (!ModuleMain.isReady()) {
                    ModuleMain.ensureInit(activity.applicationContext)
                }
                if (!ModuleMain.isReady()) {
                    Toast.makeText(activity, "模块服务未就绪，请完全重启 QQ 后再试", Toast.LENGTH_LONG).show()
                    return@post
                }
                openDialog?.dismiss()
                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setContentView(buildContent(activity) { dialog.dismiss() })
                dialog.setOnDismissListener { openDialog = null }
                dialog.window?.setBackgroundDrawable(roundRect(C_BG, 20f, activity))
                dialog.window?.setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                openDialog = dialog
                dialog.show()
            } catch (t: Throwable) {
                Log.e("DetectorPanel.show failed", t)
                Toast.makeText(activity, "打开面板失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun roundRect(color: Int, radiusDp: Float, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun pillButton(
        ctx: Context,
        text: String,
        bg: Int,
        fg: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(fg)
            textSize = 13f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = roundRect(bg, 12f, ctx)
            isClickable = true
            isFocusable = true
        }
    }

    private fun sectionLabel(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C_SUB)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(ctx, 2f), dp(ctx, 12f), 0, dp(ctx, 6f))
        }

    private fun buildContent(activity: Activity, onClose: () -> Unit): View {
        val prefs = ModuleMain.prefs
        val repo = ModuleMain.repository
        val service = ModuleMain.detectionService
        val pad = dp(activity, 14f)

        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(pad, pad, pad, pad)
        }

        // Header
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C_PRIMARY, 16f, activity)
            setPadding(dp(activity, 16f), dp(activity, 14f), dp(activity, 12f), dp(activity, 14f))
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(activity).apply {
            text = "去TM的单向好友"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        headerText.addView(TextView(activity).apply {
            text = "v${BuildConfig.VERSION_NAME} · 仅 ${Constants.QQ_PACKAGE}"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            setPadding(0, dp(activity, 2f), 0, 0)
        })
        header.addView(headerText)
        val closeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 10f), dp(activity, 4f), dp(activity, 10f), dp(activity, 4f))
            setOnClickListener { onClose() }
        }
        header.addView(closeBtn)
        outer.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 12f) }
        )

        // Status card
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 14f, activity)
            setPadding(dp(activity, 14f), dp(activity, 12f), dp(activity, 14f), dp(activity, 12f))
            elevation = dp(activity, 1f).toFloat()
        }
        val statusTitle = TextView(activity).apply {
            text = "检测状态"
            setTextColor(C_TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val statusBody = TextView(activity).apply {
            setTextColor(C_SUB)
            textSize = 12.5f
            setLineSpacing(dp(activity, 2f).toFloat(), 1f)
            setPadding(0, dp(activity, 6f), 0, 0)
        }
        val countChip = TextView(activity).apply {
            setTextColor(C_PRIMARY_DARK)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = roundRect(C_CHIP_BG, 8f, activity)
            setPadding(dp(activity, 10f), dp(activity, 5f), dp(activity, 10f), dp(activity, 5f))
            setPadding(dp(activity, 10f), dp(activity, 5f), dp(activity, 10f), dp(activity, 5f))
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusBody)
        statusCard.addView(
            countChip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8f) }
        )
        outer.addView(
            statusCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10f) }
        )

        // Account
        outer.addView(sectionLabel(activity, "账号"))
        val accountSpinner = Spinner(activity)
        val accountWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 12f, activity)
            setPadding(dp(activity, 6f), dp(activity, 2f), dp(activity, 6f), dp(activity, 2f))
        }
        accountWrap.addView(accountSpinner)
        outer.addView(accountWrap)

        // Actions
        outer.addView(sectionLabel(activity, "操作"))
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRefresh = pillButton(activity, "立即刷新", C_PRIMARY, Color.WHITE, bold = true)
        val btnClear = pillButton(activity, "清空历史", C_BTN_SECONDARY, C_TEXT)
        actionRow.addView(
            btnRefresh,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(activity, 8f)
            }
        )
        actionRow.addView(
            btnClear,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        outer.addView(actionRow)

        // Settings row
        outer.addView(sectionLabel(activity, "设置"))
        val settingsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 12f, activity)
            setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 10f))
        }
        val swNotify = Switch(activity).apply {
            text = "  系统通知（默认关）"
            setTextColor(C_TEXT)
            textSize = 13f
            isChecked = prefs.notifyEnabled
            setOnCheckedChangeListener { _, c -> prefs.notifyEnabled = c }
        }
        settingsCard.addView(swNotify)
        settingsCard.addView(TextView(activity).apply {
            text = "定时检测"
            setTextColor(C_SUB)
            textSize = 12f
            setPadding(0, dp(activity, 8f), 0, 0)
        })
        val intervalSpinner = Spinner(activity)
        intervalSpinner.adapter =
            ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, intervalLabels)
        val iIdx = intervalValues.indexOf(prefs.intervalMinutes).let { if (it >= 0) it else 0 }
        intervalSpinner.setSelection(iIdx)
        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.intervalMinutes = intervalValues[position]
                service.reschedulePeriodic()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        settingsCard.addView(intervalSpinner)
        outer.addView(settingsCard)

        // History
        outer.addView(sectionLabel(activity, "被删记录"))
        val tip = TextView(activity).apply {
            text = "无法区分对方删你或你删对方。每条可打开资料卡 / 本地聊天。"
            setTextColor(C_SUB)
            textSize = 11.5f
            setPadding(dp(activity, 2f), 0, 0, dp(activity, 6f))
        }
        outer.addView(tip)

        val historyList = ListView(activity).apply {
            divider = null
            dividerHeight = dp(activity, 8f)
            setSelector(android.R.color.transparent)
            clipToPadding = false
        }
        outer.addView(
            historyList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 280f)
            )
        )

        var accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
        var selected = prefs.uiSelectedOwnerUin ?: accounts.firstOrNull()
        var history = emptyList<DeletionRecord>()

        fun labels(): List<String> =
            if (accounts.isEmpty()) listOf("（暂无账号，请先刷新）") else accounts

        fun bindAccounts() {
            accountSpinner.adapter =
                ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels())
            val pos = accounts.indexOf(selected).let { if (it >= 0) it else 0 }
            if (accounts.isNotEmpty()) {
                accountSpinner.setSelection(pos)
                selected = accounts[pos]
            }
        }

        fun refreshStatusAndList() {
            accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
            if (selected == null) selected = accounts.firstOrNull()
            bindAccounts()
            val owner = selected
            if (owner.isNullOrBlank()) {
                statusBody.text = "尚无基线。登录 QQ 后点「立即刷新」。"
                countChip.text = "好友数 —"
                history = emptyList()
                historyList.adapter = HistoryAdapter(activity, emptyList())
                return
            }
            val acc = repo.getAccount(owner)
            val snap = repo.getSnapshot(owner)
            val friendCount = snap?.friends?.size
            statusBody.text = buildString {
                append("账号 $owner\n")
                append(
                    if (acc?.baselineAt != null)
                        "基线 ${timeFmt.format(Date(acc.baselineAt))}"
                    else "基线 未建立"
                )
                if (acc?.lastCheckAt != null) {
                    append("  ·  上次 ${timeFmt.format(Date(acc.lastCheckAt))}")
                }
                append("\n来源 ${acc?.lastSource ?: "—"}")
                if (!acc?.lastError.isNullOrBlank()) {
                    append("\n")
                    append("错误 ${acc?.lastError}")
                }
            }
            countChip.text = if (friendCount != null) "好友数 $friendCount" else "好友数 —"
            countChip.setTextColor(if ((friendCount ?: 0) > 0) C_OK else C_PRIMARY_DARK)

            history = repo.listHistory(owner)
            historyList.adapter = HistoryAdapter(activity, history) { rec, action ->
                repo.markRead(rec.id)
                when (action) {
                    HistoryAction.PROFILE ->
                        ChatLauncher.openProfile(activity, rec.friendUin, ModuleMain.classLoader)
                    HistoryAction.CHAT ->
                        ChatLauncher.openChat(activity, rec.friendUin, ModuleMain.classLoader)
                }
                refreshStatusAndList()
            }
        }

        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (accounts.isEmpty()) return
                selected = accounts[position]
                prefs.uiSelectedOwnerUin = selected
                refreshStatusAndList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnRefresh.setOnClickListener {
            statusBody.text = "检测中（合并全量好友，可能需数秒）…"
            countChip.text = "刷新中…"
            service.refreshAsync { outcome ->
                mainHandler.post {
                    val msg = when (outcome) {
                        is DetectionOutcome.BaselineCreated ->
                            "已建立基线：${outcome.count} 人（${outcome.source}）"
                        is DetectionOutcome.Checked ->
                            "完成：${outcome.previousCount}→${outcome.currentCount}，消失 ${outcome.removed.size}"
                        is DetectionOutcome.Failed -> "失败：${outcome.reason}"
                        is DetectionOutcome.Skipped -> "跳过：${outcome.reason}"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
                    if (selected == null) selected = accounts.firstOrNull()
                    refreshStatusAndList()
                }
            }
        }

        btnClear.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("清空历史")
                .setMessage("清空账号 $owner 的被删记录？\n基线快照会保留。")
                .setPositiveButton("清空") { _, _ ->
                    repo.clearHistory(owner)
                    refreshStatusAndList()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Scrollable shell for small screens
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                outer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // Note: nested ListView in ScrollView is bad; keep list fixed height instead.
        // Rebuild without outer ScrollView — use LinearLayout only
        refreshStatusAndList()
        return outer
    }

    private enum class HistoryAction { PROFILE, CHAT }

    private class HistoryAdapter(
        private val ctx: Context,
        private val items: List<DeletionRecord>,
        private val onAction: ((DeletionRecord, HistoryAction) -> Unit)? = null
    ) : BaseAdapter() {

        override fun getCount(): Int = if (items.isEmpty()) 1 else items.size
        override fun getItem(position: Int): Any =
            if (items.isEmpty()) Unit else items[position]
        override fun getItemId(position: Int): Long =
            if (items.isEmpty()) -1L else items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (items.isEmpty()) {
                return TextView(ctx).apply {
                    text = "暂无被删记录\n建立基线后，列表减少的人会出现在这里"
                    setTextColor(C_SUB)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 16f), dp(ctx, 28f), dp(ctx, 16f), dp(ctx, 28f))
                    background = roundRect(C_CARD, 14f, ctx)
                }
            }
            val rec = items[position]
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(C_CARD, 14f, ctx)
                setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val avatar = TextView(ctx).apply {
                text = rec.friendName.take(1).ifBlank { "?" }
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (rec.read) 0xFFB0B4BA.toInt() else C_PRIMARY)
                }
            }
            top.addView(avatar, LinearLayout.LayoutParams(dp(ctx, 36f), dp(ctx, 36f)))
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(ctx).apply {
                text = buildString {
                    if (!rec.read) append("● ")
                    append(rec.friendName.ifBlank { rec.friendUin })
                }
                setTextColor(C_TEXT)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            info.addView(TextView(ctx).apply {
                text = "${rec.friendUin}  ·  约 ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                        .format(Date(rec.detectedAt))
                }"
                setTextColor(C_SUB)
                textSize = 11.5f
                setPadding(0, dp(ctx, 2f), 0, 0)
            })
            top.addView(info)
            card.addView(top)

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 10f), 0, 0)
            }
            val btnProfile = pillButton(ctx, "打开资料卡", C_BTN_SECONDARY, C_TEXT)
            val btnChat = pillButton(ctx, "打开本地聊天", C_CHIP_BG, C_PRIMARY_DARK, bold = true)
            btnProfile.setOnClickListener {
                onAction?.invoke(rec, HistoryAction.PROFILE)
            }
            btnChat.setOnClickListener {
                onAction?.invoke(rec, HistoryAction.CHAT)
            }
            btnRow.addView(
                btnProfile,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(ctx, 8f)
                }
            )
            btnRow.addView(
                btnChat,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            card.addView(btnRow)
            return card
        }

        private fun dp(ctx: Context, v: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
                .toInt()

        private fun roundRect(color: Int, radiusDp: Float, ctx: Context): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(ctx, radiusDp).toFloat()
            }

        private fun pillButton(
            ctx: Context,
            text: String,
            bg: Int,
            fg: Int,
            bold: Boolean = false
        ): TextView = TextView(ctx).apply {
            this.text = text
            setTextColor(fg)
            textSize = 12.5f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8f), dp(ctx, 9f), dp(ctx, 8f), dp(ctx, 9f))
            background = roundRect(bg, 10f, ctx)
            isClickable = true
            isFocusable = true
        }
    }

    fun attachFab(activity: Activity) {
        mainHandler.post {
            try {
                val decor = activity.window?.decorView as? FrameLayout ?: return@post
                if (decor.findViewWithTag<View>(FAB_TAG) != null) return@post

                val fab = TextView(activity).apply {
                    tag = FAB_TAG
                    text = "单向好友"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(activity, 16f), dp(activity, 12f), dp(activity, 16f), dp(activity, 12f))
                    background = GradientDrawable().apply {
                        setColor(C_PRIMARY)
                        cornerRadius = dp(activity, 22f).toFloat()
                    }
                    elevation = dp(activity, 8f).toFloat()
                    setOnClickListener { show(activity) }
                }
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = dp(activity, 16f)
                    bottomMargin = dp(activity, 96f)
                }
                decor.addView(fab, lp)
                Log.i("FAB attached on ${activity.javaClass.name}")
            } catch (t: Throwable) {
                Log.d("attachFab: ${t.message}")
            }
        }
    }

    private const val FAB_TAG = "fuckqq_nullfriend_fab"
}
