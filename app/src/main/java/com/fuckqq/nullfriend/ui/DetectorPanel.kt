package com.fuckqq.nullfriend.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
 * Modern in-process panel. Uses ScrollView + LinearLayout cards (NOT ListView)
 * so action buttons receive clicks reliably.
 */
object DetectorPanel {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val timeFmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val intervalLabels = listOf("定时：关", "定时：30分", "定时：1小时", "定时：3小时")
    private val intervalValues = listOf(0, 30, 60, 180)

    // Modern dark-orange theme (readable on QQ light UIs)
    private const val BG = 0xFF0F1115.toInt()
    private const val SURFACE = 0xFF1A1D24.toInt()
    private const val SURFACE2 = 0xFF242833.toInt()
    private const val ACCENT = 0xFFFF6B35.toInt()
    private const val ACCENT_SOFT = 0x33FF6B35
    private const val TEXT = 0xFFF2F3F5.toInt()
    private const val TEXT2 = 0xFF9AA0A6.toInt()
    private const val OK = 0xFF3DDC97.toInt()
    private const val DANGER = 0xFFFF5C7A.toInt()
    private const val DIVIDER = 0xFF2C313C.toInt()

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
                dialog.setOnDismissListener { openDialog = null }

                dialog.window?.apply {
                    setBackgroundDrawable(shape(BG, 22f, activity))
                    val w = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
                    val h = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
                    setLayout(w, h)
                    // Dim behind
                    setDimAmount(0.55f)
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

    private fun shape(color: Int, radiusDp: Float, ctx: Context, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1f), stroke)
        }

    private fun ripple(normal: Int, radiusDp: Float, ctx: Context): android.graphics.drawable.Drawable {
        val content = shape(normal, radiusDp, ctx)
        return if (Build.VERSION.SDK_INT >= 21) {
            val mask = shape(Color.WHITE, radiusDp, ctx)
            RippleDrawable(
                ColorStateList.valueOf(0x44FFFFFF),
                content,
                mask
            )
        } else {
            StateListDrawable().apply {
                val pressed = shape(
                    Color.argb(
                        255,
                        (Color.red(normal) * 0.85f).toInt(),
                        (Color.green(normal) * 0.85f).toInt(),
                        (Color.blue(normal) * 0.85f).toInt()
                    ),
                    radiusDp,
                    ctx
                )
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), content)
            }
        }
    }

    private fun btn(
        ctx: Context,
        label: String,
        filled: Boolean,
        accent: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val bg = when {
            filled && accent -> ACCENT
            filled -> SURFACE2
            else -> Color.TRANSPARENT
        }
        val fg = when {
            filled && accent -> Color.WHITE
            filled -> TEXT
            else -> ACCENT
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(fg)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 44f)
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            background = if (filled) {
                ripple(bg, 14f, ctx)
            } else {
                ripple(ACCENT_SOFT, 14f, ctx).also {
                    // keep soft fill
                }
                shape(ACCENT_SOFT, 14f, ctx)
            }
            if (!filled) {
                background = shape(ACCENT_SOFT, 14f, ctx)
            }
            isClickable = true
            isFocusable = true
            // Ensure we consume clicks even if parent wants them
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener {
                try {
                    onClick()
                } catch (t: Throwable) {
                    Log.e("btn click", t)
                    toast(ctx, t.message ?: "操作失败")
                }
            }
        }
    }

    private fun buildRoot(activity: Activity, onClose: () -> Unit): View {
        val prefs = ModuleMain.prefs
        val repo = ModuleMain.repository
        val service = ModuleMain.detectionService
        val p = dp(activity, 16f)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(p, p, p, p)
        }

        // ===== Header =====
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 12f))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = "去TM的单向好友"
            setTextColor(TEXT)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(activity).apply {
            text = "检测列表消失 · v${BuildConfig.VERSION_NAME}"
            setTextColor(TEXT2)
            textSize = 12f
            setPadding(0, dp(activity, 3f), 0, 0)
        })
        header.addView(titleCol)
        header.addView(btn(activity, "关闭", filled = true, accent = false, onClick = onClose).apply {
            minWidth = dp(activity, 64f)
        })
        root.addView(header)

        // ===== Stats strip =====
        val stats = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(SURFACE, 16f, activity)
            setPadding(dp(activity, 12f), dp(activity, 12f), dp(activity, 12f), dp(activity, 12f))
        }
        val chipFriends = makeStatChip(activity, "好友", "—")
        val chipDeleted = makeStatChip(activity, "被删", "0")
        val chipSource = makeStatChip(activity, "来源", "—")
        stats.addView(chipFriends.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(chipDeleted.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(activity, 8f); rightMargin = dp(activity, 8f)
        })
        stats.addView(chipSource.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(stats, lpMatch().apply { bottomMargin = dp(activity, 10f) })

        val statusLine = TextView(activity).apply {
            setTextColor(TEXT2)
            textSize = 12f
            setLineSpacing(dp(activity, 2f).toFloat(), 1.1f)
            setPadding(dp(activity, 4f), 0, dp(activity, 4f), dp(activity, 10f))
        }
        root.addView(statusLine)

        // ===== Toolbar: account + refresh =====
        val tool = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE, 16f, activity)
            setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 12f))
        }
        tool.addView(TextView(activity).apply {
            text = "账号"
            setTextColor(TEXT2)
            textSize = 11f
        })
        val accountSpinner = Spinner(activity).apply {
            setPopupBackgroundDrawable(shape(SURFACE2, 12f, activity))
            setPadding(0, dp(activity, 4f), 0, dp(activity, 4f))
        }
        tool.addView(accountSpinner)

        val actionScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(activity, 8f), 0, 0)
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRefresh = btn(activity, "立即刷新", filled = true, accent = true) {}
        val btnNotify = btn(activity, if (prefs.notifyEnabled) "通知：开" else "通知：关", filled = true) {}
        val intervalSpinner = Spinner(activity).apply {
            setPopupBackgroundDrawable(shape(SURFACE2, 12f, activity))
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, intervalLabels)
            setSelection(intervalValues.indexOf(prefs.intervalMinutes).let { if (it >= 0) it else 0 })
        }
        // wrap spinner in a dark pill
        val intervalWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(SURFACE2, 14f, activity)
            setPadding(dp(activity, 6f), 0, dp(activity, 6f), 0)
            gravity = Gravity.CENTER_VERTICAL
            addView(intervalSpinner, LinearLayout.LayoutParams(dp(activity, 120f), dp(activity, 44f)))
        }
        val btnClear = btn(activity, "清空历史", filled = true) {}
        val btnReset = btn(activity, "重置脏数据", filled = true) {}

        fun addAction(v: View) {
            actions.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(activity, 8f) })
        }
        addAction(btnRefresh)
        addAction(btnNotify)
        addAction(intervalWrap)
        addAction(btnClear)
        addAction(btnReset)
        actionScroll.addView(actions)
        tool.addView(actionScroll)
        root.addView(tool, lpMatch().apply { bottomMargin = dp(activity, 12f) })

        // ===== History header =====
        val histHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4f), 0, dp(activity, 4f), dp(activity, 8f))
        }
        histHeader.addView(TextView(activity).apply {
            text = "被删记录"
            setTextColor(TEXT)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val histCount = TextView(activity).apply {
            setTextColor(TEXT2)
            textSize = 12f
        }
        histHeader.addView(histCount)
        root.addView(histHeader)

        // ===== Scrollable history cards (buttons work) =====
        val historyHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val historyScroll = object : ScrollView(activity) {
            // allow nested vertical scroll inside dialog
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // default is fine for simple cards
                return super.onInterceptTouchEvent(ev)
            }
        }.apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                historyHost,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            historyScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        var accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
        var selected = prefs.uiSelectedOwnerUin ?: accounts.firstOrNull()

        fun labels(): List<String> =
            if (accounts.isEmpty()) listOf("暂无账号 · 先点刷新") else accounts

        fun bindAccounts() {
            accountSpinner.adapter =
                ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels())
            val pos = accounts.indexOf(selected).let { if (it >= 0) it else 0 }
            if (accounts.isNotEmpty()) {
                accountSpinner.setSelection(pos)
                selected = accounts[pos]
            }
        }

        fun setStat(chip: Pair<LinearLayout, TextView>, value: String, color: Int = TEXT) {
            chip.second.text = value
            chip.second.setTextColor(color)
        }

        fun renderHistory(list: List<DeletionRecord>) {
            historyHost.removeAllViews()
            histCount.text = "${list.size} 条"
            if (list.isEmpty()) {
                historyHost.addView(emptyState(activity))
                return
            }
            list.forEachIndexed { index, rec ->
                historyHost.addView(
                    historyCard(activity, rec) { action ->
                        repo.markRead(rec.id)
                        when (action) {
                            "profile" -> ChatLauncher.openProfile(
                                activity, rec.friendUin, ModuleMain.classLoader
                            )
                            "chat" -> ChatLauncher.openChat(
                                activity, rec.friendUin, ModuleMain.classLoader
                            )
                        }
                        // soft refresh unread dots only
                        val owner = selected
                        if (owner != null) {
                            renderHistory(repo.listHistory(owner))
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = dp(activity, 10f)
                    }
                )
            }
        }

        fun refreshAll() {
            accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
            if (selected == null) selected = accounts.firstOrNull()
            bindAccounts()
            val owner = selected
            if (owner.isNullOrBlank()) {
                statusLine.text = "登录 QQ 后点「立即刷新」建立基线（首次不会报删）。"
                setStat(chipFriends, "—")
                setStat(chipDeleted, "0")
                setStat(chipSource, "—")
                renderHistory(emptyList())
                return
            }
            val acc = repo.getAccount(owner)
            val snap = repo.getSnapshot(owner)
            val fc = snap?.friends?.size
            val hist = repo.listHistory(owner)
            setStat(chipFriends, fc?.toString() ?: "—", if ((fc ?: 0) > 10) OK else ACCENT)
            setStat(chipDeleted, hist.size.toString(), if (hist.isNotEmpty()) DANGER else TEXT2)
            val tag = try {
                com.fuckqq.nullfriend.provider.FriendRoster.lastSourceTag
            } catch (_: Throwable) {
                acc?.lastSource?.name ?: "—"
            }
            setStat(chipSource, tag)
            statusLine.text = buildString {
                append(owner)
                if (acc?.baselineAt != null) {
                    append("  ·  基线 ")
                    append(timeFmtFull.format(Date(acc.baselineAt)))
                } else append("  ·  未建基线")
                if (acc?.lastCheckAt != null) {
                    append("\n上次检测 ")
                    append(timeFmtFull.format(Date(acc.lastCheckAt)))
                }
                if (!acc?.lastError.isNullOrBlank()) {
                    append("\n")
                    append(acc?.lastError)
                }
            }
            renderHistory(hist)
        }

        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (accounts.isEmpty()) return
                selected = accounts[position]
                prefs.uiSelectedOwnerUin = selected
                refreshAll()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Wire buttons with real handlers (created empty above)
        btnRefresh.setOnClickListener {
            statusLine.text = "正在合并全量好友，请稍候…"
            setStat(chipFriends, "…", ACCENT)
            service.refreshAsync { outcome ->
                mainHandler.post {
                    val msg = when (outcome) {
                        is DetectionOutcome.BaselineCreated ->
                            "基线已建立：${outcome.count} 人"
                        is DetectionOutcome.Checked ->
                            "完成 ${outcome.previousCount}→${outcome.currentCount}，消失 ${outcome.removed.size}"
                        is DetectionOutcome.Failed -> "失败：${outcome.reason}"
                        is DetectionOutcome.Skipped -> "跳过：${outcome.reason}"
                    }
                    toast(activity, msg)
                    accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
                    if (selected == null) selected = accounts.firstOrNull()
                    refreshAll()
                }
            }
        }
        btnNotify.setOnClickListener {
            prefs.notifyEnabled = !prefs.notifyEnabled
            btnNotify.text = if (prefs.notifyEnabled) "通知：开" else "通知：关"
            toast(activity, if (prefs.notifyEnabled) "已开启系统通知" else "已关闭系统通知")
        }
        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.intervalMinutes = intervalValues[position]
                service.reschedulePeriodic()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnClear.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("清空历史")
                .setMessage("清空 $owner 的被删记录？基线保留。")
                .setPositiveButton("清空") { _, _ ->
                    repo.clearHistory(owner)
                    refreshAll()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btnReset.setOnClickListener {
            val owner = selected ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("重置脏数据")
                .setMessage(
                    "将删除该账号的错误基线与被删记录（例如 10001 假好友）。\n" +
                        "之后请重新点「立即刷新」建立真实基线。"
                )
                .setPositiveButton("重置") { _, _ ->
                    service.resetDirtyData(owner)
                    toast(activity, "已重置，请再点立即刷新")
                    refreshAll()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Sync notify label
        btnNotify.text = if (prefs.notifyEnabled) "通知：开" else "通知：关"

        // Auto-offer reset if history is serial garbage
        val histProbe = selected?.let { repo.listHistory(it) }.orEmpty()
        if (histProbe.size >= 8 &&
            com.fuckqq.nullfriend.util.UinUtil.looksLikeSerialGarbage(histProbe.map { it.friendUin })
        ) {
            statusLine.text =
                "检测到错误被删记录（10001 序号垃圾数据）。请点「重置脏数据」后重新刷新。"
        }

        refreshAll()
        return root
    }

    private fun makeStatChip(ctx: Context, label: String, value: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(SURFACE2, 12f, ctx)
            setPadding(dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f))
        }
        box.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT2)
            textSize = 11f
            gravity = Gravity.CENTER
        })
        val v = TextView(ctx).apply {
            text = value
            setTextColor(TEXT)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 2f), 0, 0)
        }
        box.addView(v)
        return box to v
    }

    private fun emptyState(ctx: Context): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(SURFACE, 16f, ctx)
            setPadding(dp(ctx, 20f), dp(ctx, 36f), dp(ctx, 20f), dp(ctx, 36f))
            addView(TextView(ctx).apply {
                text = "暂无被删记录"
                setTextColor(TEXT)
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = "先「立即刷新」建基线。\n之后从列表消失的人会出现在这里。"
                setTextColor(TEXT2)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 8f), 0, 0)
            })
        }

    private fun historyCard(
        ctx: Context,
        rec: DeletionRecord,
        onAction: (String) -> Unit
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE, 16f, ctx, DIVIDER)
            setPadding(dp(ctx, 14f), dp(ctx, 14f), dp(ctx, 14f), dp(ctx, 12f))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = TextView(ctx).apply {
            val ch = rec.friendName.trim().firstOrNull()?.toString() ?: "#"
            text = ch
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (rec.read) 0xFF5A6070.toInt() else ACCENT)
            }
        }
        top.addView(avatar, LinearLayout.LayoutParams(dp(ctx, 42f), dp(ctx, 42f)))

        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        meta.addView(TextView(ctx).apply {
            text = buildString {
                if (!rec.read) append("● ")
                append(rec.friendName.ifBlank { rec.friendUin })
            }
            setTextColor(TEXT)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
        })
        meta.addView(TextView(ctx).apply {
            text = "${rec.friendUin}  ·  ${timeFmt.format(Date(rec.detectedAt))}"
            setTextColor(TEXT2)
            textSize = 12f
            setPadding(0, dp(ctx, 3f), 0, 0)
        })
        top.addView(meta)
        card.addView(top)

        // Action buttons — NOT inside ListView, so clicks work
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 12f), 0, 0)
        }
        val b1 = btn(ctx, "打开资料卡", filled = true, accent = false) { onAction("profile") }
        val b2 = btn(ctx, "打开本地聊天", filled = true, accent = true) { onAction("chat") }
        row.addView(
            b1,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(ctx, 8f)
            }
        )
        row.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun lpMatch() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    fun attachFab(activity: Activity) {
        mainHandler.post {
            try {
                val decor = activity.window?.decorView as? FrameLayout ?: return@post
                if (decor.findViewWithTag<View>(FAB_TAG) != null) return@post
                val fab = TextView(activity).apply {
                    tag = FAB_TAG
                    text = "单向好友"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    minHeight = dp(activity, 48f)
                    setPadding(dp(activity, 18f), dp(activity, 12f), dp(activity, 18f), dp(activity, 12f))
                    background = ripple(ACCENT, 24f, activity)
                    elevation = dp(activity, 10f).toFloat()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { show(activity) }
                }
                decor.addView(
                    fab,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        rightMargin = dp(activity, 16f)
                        bottomMargin = dp(activity, 100f)
                    }
                )
                Log.i("FAB attached on ${activity.javaClass.name}")
            } catch (t: Throwable) {
                Log.d("attachFab: ${t.message}")
            }
        }
    }

    private const val FAB_TAG = "fuckqq_nullfriend_fab"
}
