package com.fuckqq.nullfriend.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuckqq.nullfriend.BuildConfig
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.domain.DetectionOutcome
import com.fuckqq.nullfriend.service.ChatLauncher
import com.fuckqq.nullfriend.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内面板 —— ark-ui 设计语言（ark 家族，moderate 深度）。
 *
 * - 近黑舞台 + 信号青仪表，方形几何，1px 规线，编辑式层级
 * - 自定义账号/定时间隔选择器（替代原生 Spinner）
 * - RecyclerView + DiffUtil 异步历史加载
 */
object DetectorPanel {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val timeFmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val intervalLabels = listOf("关闭", "30 分", "1 小时", "3 小时", "半天")
    private val intervalValues = listOf(0, 30, 60, 180, 720)

    private const val FILTER_ALL = 0
    private const val FILTER_UNREAD = 1

    @Volatile
    private var openDialog: Dialog? = null

    fun show(activity: Activity) {
        mainHandler.post {
            try {
                if (!ModuleMain.isReady()) {
                    ModuleMain.ensureInit(activity.applicationContext)
                }
                if (!ModuleMain.isReady()) {
                    toast(activity, "模块未就绪，请强停 QQ 后重开")
                    return@post
                }
                openDialog?.dismiss()

                val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val root = buildRoot(activity) { dialog.dismiss() }
                dialog.setContentView(root)
                dialog.setCancelable(true)
                dialog.setOnDismissListener {
                    openDialog = null
                    // 面板关闭后刷新联系人列表底部入口的未读角标
                    try { com.fuckqq.nullfriend.hook.ContactsEntryHook.refreshBadges() } catch (_: Throwable) {}
                }

                dialog.window?.apply {
                    val T = UiTheme
                    setBackgroundDrawable(T.shape(T.INK, 2f, activity))
                    val w = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
                    val h = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                    setLayout(w, h)
                    setDimAmount(0.6f)
                }
                openDialog = dialog
                dialog.show()
            } catch (t: Throwable) {
                Log.e("DetectorPanel.show failed", t)
                toast(activity, "打开失败: ${t.message}")
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun buildRoot(activity: Activity, onClose: () -> Unit): View {
        val T = UiTheme
        val prefs = ModuleMain.prefs
        val repo = ModuleMain.repository
        val service = ModuleMain.detectionService
        val dp = { v: Float -> T.dp(activity, v) }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_4), dp(T.SP_4), dp(T.SP_4), dp(T.SP_4))
        }

        // ===== Header =====
        root.addView(buildHeader(activity, onClose))

        // 信号规线
        root.addView(View(activity).apply { setBackgroundColor(T.SIGNAL) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(T.SP_2); bottomMargin = dp(T.SP_3)
            })

        // ===== Stats strip =====
        val stats = buildStats(activity)
        root.addView(stats.host, lpMatch().apply { bottomMargin = dp(T.SP_3) })

        // ===== Status line =====
        val statusLine = TextView(activity).apply {
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_BODY_2
            typeface = T.typefaceMono()
            setLineSpacing(dp(2f).toFloat(), 1.1f)
            setPadding(dp(T.SP_1), 0, dp(T.SP_1), dp(T.SP_3))
        }
        root.addView(statusLine)

        // ===== Search bar =====
        val searchBox = buildSearchBox(activity)
        root.addView(searchBox.host, lpMatch().apply { bottomMargin = dp(T.SP_3) })

        // ===== Account selector =====
        val accountBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            setPadding(dp(T.SP_3), dp(T.SP_2), dp(T.SP_3), dp(T.SP_2))
        }
        accountBar.addView(TextView(activity).apply {
            text = "ACCOUNT"
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
        })
        val accountTrigger = TextView(activity).apply {
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            typeface = T.typefaceMedium()
            setPadding(0, dp(T.SP_1), 0, dp(T.SP_1))
            isClickable = true
            val arrow = TextView(activity) // placeholder
        }
        accountBar.addView(accountTrigger)
        root.addView(accountBar, lpMatch().apply { bottomMargin = dp(T.SP_2) })

        // ===== Primary action: 立即刷新 =====
        val btnRefresh = makeBtn(activity, "立即刷新", true, true) {}
        val refreshSpinner = ArkProgress(activity).apply {
            stop()  // 初始隐藏
        }
        val refreshRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        refreshRow.addView(btnRefresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(T.SP_2)
        })
        refreshRow.addView(refreshSpinner)
        root.addView(refreshRow, lpMatch().apply { bottomMargin = dp(T.SP_2) })

        // ===== Secondary actions =====
        val actionScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnNotify = makeBtn(activity, if (prefs.notifyEnabled) "通知 ON" else "通知 OFF", false, false) {}
        val btnInterval = makeBtn(activity, "定时：" + intervalLabel(prefs.intervalMinutes), false, false) {}
        val btnFilter = makeBtn(activity, "全部", false, false) {}
        val btnExport = makeBtn(activity, "导出", false, false) {}
        val btnClear = makeBtn(activity, "清空", false, false) {}
        val btnReset = makeBtn(activity, "重置", false, false) {}
        fun addAction(v: View) {
            actions.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(T.SP_2) })
        }
        addAction(btnNotify); addAction(btnInterval); addAction(btnFilter)
        addAction(btnExport); addAction(btnClear); addAction(btnReset)
        actionScroll.addView(actions)
        root.addView(actionScroll, lpMatch().apply { bottomMargin = dp(T.SP_3) })

        // ===== History header =====
        val histHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        histHeader.addView(TextView(activity).apply {
            text = "被删记录"
            setTextColor(T.TEXT)
            textSize = T.TEXT_TITLE_2
            typeface = T.typefaceBold()
        })
        val histCount = TextView(activity).apply {
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_BODY_2
            typeface = T.typefaceMono()
            setPadding(dp(T.SP_2), 0, dp(T.SP_3), 0)
        }
        histHeader.addView(histCount)
        histHeader.addView(View(activity).apply { setBackgroundColor(T.RULE) },
            LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(histHeader, lpMatch().apply { bottomMargin = dp(T.SP_2) })

        // ===== state =====
        var accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
        var selected: String? = prefs.uiSelectedOwnerUin ?: accounts.firstOrNull()
        var currentFilter = FILTER_ALL
        lateinit var historyAdapter: HistoryAdapter

        // ===== empty state =====
        val emptyView = buildEmptyState(activity)
        root.addView(emptyView, lpMatch().apply { bottomMargin = dp(T.SP_2) })

        // ===== RecyclerView =====
        val adapter = HistoryAdapter(activity,
            onClick = { rec ->
                repo.markRead(rec.id)
                ChatLauncher.openProfile(activity, rec.friendUin, ModuleMain.classLoader)
                refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
            },
            onLongClick = { rec ->
                repo.markRead(rec.id)
                ChatLauncher.openChat(activity, rec.friendUin, ModuleMain.classLoader)
                refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
            }
        )
        historyAdapter = adapter
        val rv = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            setHasFixedSize(false)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, 0, 0, dp(T.SP_1))
        }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun bindAccounts() {
            if (accounts.isEmpty()) {
                accountTrigger.text = "暂无账号 · 先点刷新"
                selected = null
            } else {
                val pos = accounts.indexOf(selected).let { if (it >= 0) it else 0 }
                selected = accounts[pos]
                accountTrigger.text = selected
            }
        }

        fun refreshStats() {
            val owner = selected
            if (owner.isNullOrBlank()) {
                stats.setFriends("—")
                stats.setDeleted("0", T.TEXT_2)
                stats.setSource("—")
                statusLine.text = "登录 QQ 后点「立即刷新」建立基线（首次不会报删）。"
                return
            }
            val acc = repo.getAccount(owner)
            val snap = repo.getSnapshot(owner)
            val hist = repo.listHistory(owner)
            val fc = snap?.friends?.size
            stats.setFriends(fc?.toString() ?: "—", if ((fc ?: 0) > 10) T.STATE_OK else T.SIGNAL)
            stats.setDeleted(hist.size.toString(), if (hist.isNotEmpty()) T.DANGER else T.TEXT_2)
            val unread = repo.unreadCount(owner)
            stats.setSource(if (unread > 0) "$unread" else "OK", if (unread > 0) T.UNREAD else T.STATE_OK)
            statusLine.text = buildString {
                append(owner)
                if (acc?.baselineAt != null) append("  ·  基线 ").append(timeFmtFull.format(Date(acc.baselineAt)))
                else append("  ·  未建基线")
                if (acc?.lastCheckAt != null) append("\n检测 ").append(timeFmtFull.format(Date(acc.lastCheckAt)))
                if (!acc?.lastError.isNullOrBlank()) append("\n⚠ ").append(acc?.lastError)
            }
        }

        // 账号选择器点击 → 弹出自定义列表
        accountTrigger.setOnClickListener {
            if (accounts.isEmpty()) return@setOnClickListener
            showListSelector(activity, "ACCOUNT", accounts,
                current = selected,
                anchor = accountTrigger) { picked ->
                selected = picked
                prefs.uiSelectedOwnerUin = picked
                bindAccounts()
                refreshStats()
                refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
            }
        }

        searchBox.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refreshHistory(activity, repo, selected, s?.toString() ?: "", currentFilter, historyAdapter, histCount, emptyView)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnRefresh.setOnClickListener {
            statusLine.text = "正在合并全量好友，请稍候…"
            stats.setFriends("…", T.SIGNAL)
            btnRefresh.visibility = View.GONE
            refreshSpinner.start()
            service.refreshAsync { outcome ->
                mainHandler.post {
                    btnRefresh.visibility = View.VISIBLE
                    refreshSpinner.stop()
                    val msg = when (outcome) {
                        is DetectionOutcome.BaselineCreated -> "基线已建立：${outcome.count} 人"
                        is DetectionOutcome.Checked -> "完成 ${outcome.previousCount}→${outcome.currentCount}，消失 ${outcome.removed.size}"
                        is DetectionOutcome.Failed -> "失败：${outcome.reason}"
                        is DetectionOutcome.Skipped -> "跳过：${outcome.reason}"
                    }
                    toast(activity, msg)
                    accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
                    if (selected == null) selected = accounts.firstOrNull()
                    bindAccounts()
                    refreshStats()
                    refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
                }
            }
        }

        btnNotify.setOnClickListener {
            prefs.notifyEnabled = !prefs.notifyEnabled
            btnNotify.text = if (prefs.notifyEnabled) "通知 ON" else "通知 OFF"
            toast(activity, if (prefs.notifyEnabled) "已开启系统通知" else "已关闭系统通知")
        }

        btnInterval.setOnClickListener {
            // 条件：首刷建基线后才可设定
            val owner = selected
            val acc = owner?.let { repo.getAccount(it) }
            if (acc?.baselineAt == null) {
                toast(activity, "请先「立即刷新」建立基线后再设定定时检查")
                return@setOnClickListener
            }
            showListSelector(activity, "定时检测 · TIMER", intervalLabels,
                current = intervalLabel(prefs.intervalMinutes),
                anchor = btnInterval) { picked ->
                val idx = intervalLabels.indexOf(picked)
                if (idx >= 0) {
                    prefs.intervalMinutes = intervalValues[idx]
                    btnInterval.text = "定时：" + picked
                    service.reschedulePeriodic()
                    toast(activity, if (intervalValues[idx] == 0) "已关闭定时检查" else "定时检查：每 $picked")
                }
            }
        }

        btnFilter.setOnClickListener {
            currentFilter = if (currentFilter == FILTER_ALL) FILTER_UNREAD else FILTER_ALL
            btnFilter.text = if (currentFilter == FILTER_UNREAD) "未读" else "全部"
            refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
        }

        btnExport.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            val text = repo.exportHistoryText(owner)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "被删记录 $owner")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            try {
                activity.startActivity(Intent.createChooser(send, "导出被删记录"))
            } catch (t: Throwable) {
                toast(activity, "导出失败: ${t.message}")
            }
        }

        btnClear.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            arkConfirm(activity, "清空历史", "清空 $owner 的被删记录？基线保留。", "清空") {
                repo.clearHistory(owner)
                refreshStats()
                refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
                toast(activity, "已清空")
            }
        }

        btnReset.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            arkConfirm(activity, "重置脏数据", "将删除该账号的错误基线与被删记录。\n之后请重新点「立即刷新」建立真实基线。", "重置") {
                service.resetDirtyData(owner)
                toast(activity, "已重置，请再点立即刷新")
                refreshStats()
                refreshHistory(activity, repo, selected, searchBox.edit.text.toString(), currentFilter, historyAdapter, histCount, emptyView)
            }
        }

        // auto-warn garbage
        val histProbe = selected?.let { repo.listHistory(it) }.orEmpty()
        if (histProbe.size >= 8 &&
            com.fuckqq.nullfriend.util.UinUtil.looksLikeSerialGarbage(histProbe.map { it.friendUin })
        ) {
            statusLine.text = "检测到错误被删记录（10001 序号垃圾数据）。请点「重置」后重新刷新。"
        }

        bindAccounts()
        refreshStats()
        refreshHistory(activity, repo, selected, "", FILTER_ALL, historyAdapter, histCount, emptyView)
        return root
    }

    private fun intervalLabel(minutes: Int): String =
        intervalLabels[intervalValues.indexOf(minutes).let { if (it >= 0) it else 0 }]

    // ============ components ============

    private class StatsHolder(val host: LinearLayout, val friends: TextView, val deleted: TextView, val source: TextView) {
        fun setFriends(v: String, color: Int = UiTheme.TEXT) { friends.text = v; friends.setTextColor(color) }
        fun setDeleted(v: String, color: Int = UiTheme.TEXT_2) { deleted.text = v; deleted.setTextColor(color) }
        fun setSource(v: String, color: Int = UiTheme.TEXT_2) { source.text = v; source.setTextColor(color) }
    }

    private fun buildStats(activity: Activity): StatsHolder {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        val host = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun chip(label: String, value: String): TextView {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
                setPadding(dp(T.SP_2), dp(T.SP_3), dp(T.SP_2), dp(T.SP_3))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(T.SP_1); rightMargin = dp(T.SP_1)
                }
            }
            box.addView(TextView(activity).apply {
                text = label
                setTextColor(T.SIGNAL)
                textSize = T.TEXT_MICRO
                typeface = T.typefaceMono()
                gravity = Gravity.CENTER
                letterSpacing = 0.14f
            })
            val v = TextView(activity).apply {
                text = value
                setTextColor(T.TEXT)
                textSize = T.TEXT_DISPLAY
                typeface = T.typefaceBold()
                gravity = Gravity.CENTER
                setPadding(0, dp(2f), 0, 0)
            }
            box.addView(v)
            host.addView(box)
            return v
        }
        val f = chip("FRIENDS", "—")
        val d = chip("REMOVED", "0")
        val s = chip("STATUS", "—")
        return StatsHolder(host, f, d, s)
    }

    private class SearchBox(val host: LinearLayout, val edit: EditText)

    private fun buildSearchBox(activity: Activity): SearchBox {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        val host = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            setPadding(dp(T.SP_3), dp(T.SP_2), dp(T.SP_3), dp(T.SP_2))
        }
        host.addView(TextView(activity).apply {
            text = "⌕"
            setTextColor(T.TEXT_3)
            textSize = T.TEXT_TITLE
            setPadding(0, 0, dp(T.SP_2), 0)
        })
        val edit = EditText(activity).apply {
            hint = "搜索 QQ 号 / 昵称"
            setHintTextColor(T.TEXT_3)
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY
            background = null
            setSingleLine()
        }
        host.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return SearchBox(host, edit)
    }

    private fun buildHeader(activity: Activity, onClose: () -> Unit): View {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = "单向好友"
            setTextColor(T.TEXT)
            textSize = T.TEXT_DISPLAY_LG
            typeface = T.typefaceBold()
            letterSpacing = -0.02f
            setLineSpacing(dp(-4f).toFloat(), 1f)
        })
        titleCol.addView(TextView(activity).apply {
            text = "DELETED FRIEND DETECTOR · v${BuildConfig.VERSION_NAME}"
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
            setPadding(0, dp(2f), 0, 0)
        })
        header.addView(titleCol)
        header.addView(makeBtn(activity, "✕", false, false, onClose).apply { minWidth = dp(40f) })
        return header
    }

    private fun buildEmptyState(activity: Activity): View {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            setPadding(dp(T.SP_5), dp(36f), dp(T.SP_5), dp(36f))
            visibility = View.GONE
            addView(TextView(activity).apply {
                text = "⌖"
                setTextColor(T.SIGNAL)
                textSize = 28f
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                text = "暂无被删记录"
                setTextColor(T.TEXT)
                textSize = T.TEXT_TITLE_2
                gravity = Gravity.CENTER
                typeface = T.typefaceBold()
                setPadding(0, dp(T.SP_2), 0, dp(T.SP_1))
            })
            addView(TextView(activity).apply {
                text = "点击「立即刷新」建立基线\n列表消失的好友将出现在此"
                setTextColor(T.TEXT_2)
                textSize = T.TEXT_BODY
                gravity = Gravity.CENTER
            })
        }
    }

    private fun makeBtn(
        ctx: Context, label: String, filled: Boolean, accent: Boolean, onClick: () -> Unit
    ): TextView {
        val T = UiTheme
        val bg = when {
            filled && accent -> T.SIGNAL
            filled -> T.SURFACE_HI
            else -> T.SURFACE
        }
        val fg = when {
            filled && accent -> T.INK
            filled -> T.TEXT
            else -> T.TEXT
        }
        val stroke = if (filled && accent) null else T.RULE
        return TextView(ctx).apply {
            text = label
            setTextColor(fg)
            textSize = T.TEXT_BODY_2
            typeface = T.typefaceMedium()
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 40f)
            setPadding(dp(ctx, T.SP_3), dp(ctx, T.SP_2), dp(ctx, T.SP_3), dp(ctx, T.SP_2))
            background = T.ripple(bg, T.RADIUS_NONE, ctx, stroke)
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.75f; false }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; false }
                    else -> false
                }
            }
            setOnClickListener {
                try { onClick() } catch (t: Throwable) {
                    Log.e("btn click", t); toast(ctx, t.message ?: "操作失败")
                }
            }
        }
    }

    /** ark 风格列表选择器（PopupWindow），替代原生 Spinner */
    private fun showListSelector(
        activity: Activity, title: String, items: List<String>,
        current: String?, anchor: View, onPick: (String) -> Unit
    ) {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
        }
        container.addView(TextView(activity).apply {
            text = title
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
            setPadding(dp(T.SP_1), 0, dp(T.SP_1), dp(T.SP_2))
        })
        items.forEach { item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
                isClickable = true
                background = T.ripple(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            }
            // 选中态左竖条
            row.addView(View(activity).apply {
                setBackgroundColor(if (item == current) T.SIGNAL else android.graphics.Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(3f), dp(20f)).apply { rightMargin = dp(T.SP_3) })
            row.addView(TextView(activity).apply {
                text = item
                setTextColor(if (item == current) T.SIGNAL else T.TEXT)
                textSize = T.TEXT_BODY_1
                typeface = if (item == current) T.typefaceMedium() else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                onPick(item)
                popupRef?.dismiss()
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(T.SP_1) })
        }
        val pw = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(T.shape(T.INK, 0f, activity, T.RULE))
            elevation = dp(activity, 8f).toFloat()
            isOutsideTouchable = true
        }
        popupRef = pw
        pw.showAsDropDown(anchor, 0, dp(T.SP_1))
    }

    @Volatile
    private var popupRef: PopupWindow? = null

    /** ark 风格确认对话框 */
    private fun arkConfirm(activity: Activity, title: String, msg: String, positive: String, onOk: () -> Unit) {
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
            text = msg
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            setLineSpacing(dp(2f).toFloat(), 1.2f)
            setPadding(0, dp(T.SP_2), 0, dp(T.SP_4))
        })
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makeBtn(activity, "取消", false, false) {}.apply {
            setOnClickListener { confirmRef?.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(T.SP_2) })
        btnRow.addView(makeBtn(activity, positive, true, true) { onOk(); confirmRef?.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCancelable(true)
            window?.apply {
                setBackgroundDrawable(T.shape(T.INK, 2f, activity))
                setDimAmount(0.5f)
            }
        }
        confirmRef = dialog
        dialog.show()
    }

    @Volatile
    private var confirmRef: Dialog? = null

    private fun refreshHistory(
        activity: Activity,
        repo: com.fuckqq.nullfriend.data.DetectorRepository,
        owner: String?,
        query: String,
        filter: Int,
        adapter: HistoryAdapter,
        histCount: TextView,
        emptyView: View
    ) {
        if (owner.isNullOrBlank()) {
            adapter.submitList(emptyList())
            histCount.text = "0"
            emptyView.visibility = View.VISIBLE
            return
        }
        Thread {
            val all = repo.listHistory(owner)
            val filtered = all.filter { rec ->
                (filter != FILTER_UNREAD || !rec.read) &&
                    (query.isBlank() ||
                        rec.friendUin.contains(query, true) ||
                        rec.friendName.contains(query, true))
            }
            mainHandler.post {
                adapter.submitList(filtered)
                histCount.text = "${filtered.size}"
                emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun lpMatch() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
