package com.fuckqq.nullfriend.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.R
import com.fuckqq.nullfriend.data.DetectorRepository
import com.fuckqq.nullfriend.data.Prefs
import com.fuckqq.nullfriend.domain.DetectionOutcome
import com.fuckqq.nullfriend.service.ChatLauncher
import com.fuckqq.nullfriend.service.DetectionService
import com.fuckqq.nullfriend.util.UinUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetectorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repository: DetectorRepository
    private var detectionService: DetectionService? = null
    private var injectedMode = false

    private lateinit var statusText: TextView
    private lateinit var accountContainer: android.widget.FrameLayout
    private lateinit var accountSelector: ArkSelector
    private lateinit var historyList: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var histCount: TextView
    private lateinit var emptyView: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var statsStrip: LinearLayout

    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var statFriends: TextView
    private lateinit var statDeleted: TextView
    private lateinit var statSource: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var refreshSpinner: ArkProgress
    private lateinit var btnTimer: TextView

    private var accounts: List<String> = emptyList()
    private var selectedOwner: String? = null
    private var currentFilter = 0 // 0=all 1=unread

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    // 定时间隔：关闭 / 30分 / 1小时 / 3小时 / 半天(12小时)
    private val intervalLabels = listOf("关闭", "30 分", "1 小时", "3 小时", "半天")
    private val intervalValues = listOf(0, 30, 60, 180, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detector)

        statusText = findViewById(R.id.statusText)
        accountContainer = findViewById(R.id.accountContainer)
        historyList = findViewById(R.id.historyList)
        searchBox = findViewById(R.id.searchBox)
        histCount = findViewById(R.id.histCount)
        emptyView = findViewById(R.id.emptyView)
        actionsRow = findViewById(R.id.actionsRow)
        statsStrip = findViewById(R.id.statsStrip)

        bindServices()
        buildStats()
        buildAccountSelector()
        buildActions()

        historyAdapter = HistoryAdapter(this,
            onClick = { rec ->
                repository.markRead(rec.id)
                ChatLauncher.openProfile(this, rec.friendUin, ModuleMain.classLoader)
                loadHistory()
            },
            onLongClick = { rec ->
                repository.markRead(rec.id)
                ChatLauncher.openChat(this, rec.friendUin, ModuleMain.classLoader)
                loadHistory()
            }
        )
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = historyAdapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { loadHistory() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        intent.getStringExtra(EXTRA_OWNER_UIN)?.let {
            selectedOwner = it
            prefs.uiSelectedOwnerUin = it
        }
        reloadUi()
    }

    private fun bindServices() {
        try {
            if (ModuleMain.appContext != null) {
                prefs = ModuleMain.prefs
                repository = ModuleMain.repository
                detectionService = ModuleMain.detectionService
                injectedMode = true
                return
            }
        } catch (_: Throwable) {}
        prefs = Prefs(this)
        repository = DetectorRepository(this)
        detectionService = null
        injectedMode = false
        statusText.text = "模块未注入 QQ 进程，仅可浏览本进程数据。请从 QQ 内入口打开。"
    }

    private fun buildStats() {
        statsStrip.removeAllViews()
        statFriends = makeStatChip("FRIENDS", "—")
        statDeleted = makeStatChip("REMOVED", "0")
        statSource = makeStatChip("STATUS", "—")
    }

    private fun makeStatChip(label: String, value: String): TextView {
        val T = UiTheme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = T.shape(T.SURFACE, T.RADIUS_NONE, this@DetectorActivity, T.RULE)
            setPadding(T.dp(this@DetectorActivity, T.SP_2), T.dp(this@DetectorActivity, T.SP_3),
                T.dp(this@DetectorActivity, T.SP_2), T.dp(this@DetectorActivity, T.SP_3))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = T.dp(this@DetectorActivity, T.SP_1)
                rightMargin = T.dp(this@DetectorActivity, T.SP_1)
            }
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
        })
        val v = TextView(this).apply {
            text = value
            setTextColor(T.TEXT)
            textSize = T.TEXT_DISPLAY
            typeface = T.typefaceBold()
            gravity = Gravity.CENTER
            setPadding(0, T.dp(this@DetectorActivity, 2f), 0, 0)
        }
        box.addView(v)
        statsStrip.addView(box)
        return v
    }

    private fun buildAccountSelector() {
        accountSelector = ArkSelector(this, "ACCOUNT").apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        accountContainer.addView(accountSelector)
        accountSelector.onSelect = { picked ->
            if (accounts.isNotEmpty()) {
                selectedOwner = picked
                prefs.uiSelectedOwnerUin = picked
                refreshStatus()
                loadHistory()
                updateTimerButtonState()
            }
        }
    }

    private fun buildActions() {
        val T = UiTheme
        actionsRow.removeAllViews()

        fun btn(label: String, filled: Boolean, accent: Boolean, onClick: (View) -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(when { accent -> T.INK; filled -> T.TEXT; else -> T.TEXT })
                textSize = T.TEXT_BODY_2
                typeface = T.typefaceMedium()
                gravity = Gravity.CENTER
                minHeight = T.dp(this@DetectorActivity, 40f)
                setPadding(T.dp(this@DetectorActivity, T.SP_3), T.dp(this@DetectorActivity, T.SP_2),
                    T.dp(this@DetectorActivity, T.SP_3), T.dp(this@DetectorActivity, T.SP_2))
                background = when {
                    accent -> T.ripple(T.SIGNAL, T.RADIUS_NONE, this@DetectorActivity)
                    filled -> T.ripple(T.SURFACE_HI, T.RADIUS_NONE, this@DetectorActivity, T.RULE)
                    else -> T.shape(T.SURFACE, T.RADIUS_NONE, this@DetectorActivity, T.RULE)
                }
                isClickable = true
                setOnClickListener { onClick(this) }
            }

        btnRefresh = btn("立即刷新", true, true) {}
        refreshSpinner = ArkProgress(this).apply { stop() }
        val btnNotify = btn(if (prefs.notifyEnabled) "通知 ON" else "通知 OFF", true, false) { v ->
            prefs.notifyEnabled = !prefs.notifyEnabled
            (v as TextView).text = if (prefs.notifyEnabled) "通知 ON" else "通知 OFF"
        }
        // 定时检查按钮：首刷建基线后才可用
        btnTimer = btn("定时：" + intervalLabel(prefs.intervalMinutes), true, false) {}
        val btnFilter = btn("全部", true, false) { v ->
            currentFilter = if (currentFilter == 0) 1 else 0
            (v as TextView).text = if (currentFilter == 1) "未读" else "全部"
            loadHistory()
        }
        val btnExport = btn("导出", true, false) {
            val owner = selectedOwner
            if (!owner.isNullOrBlank()) {
                val text = repository.exportHistoryText(owner)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "被删记录 $owner")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try { startActivity(Intent.createChooser(send, "导出被删记录")) }
                catch (t: Throwable) { Toast.makeText(this, "导出失败: ${t.message}", Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show()
            }
        }
        val btnClear = btn("清空", true, false) { confirmClear() }
        val btnReset = btn("重置", true, false) { confirmReset() }

        fun add(v: View) {
            actionsRow.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = T.dp(this@DetectorActivity, T.SP_2) })
        }
        add(btnRefresh); add(refreshSpinner); add(btnNotify); add(btnTimer)
        add(btnFilter); add(btnExport); add(btnClear); add(btnReset)

        btnTimer.setOnClickListener {
            // 条件：首次刷新建基线后才可设定
            val owner = selectedOwner
            val acc = owner?.let { repository.getAccount(it) }
            if (acc?.baselineAt == null) {
                Toast.makeText(this, "请先「立即刷新」建立基线后再设定定时检查", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            accountSelector.dismissPopup()
            showIntervalPopup()
        }

        btnRefresh.setOnClickListener {
            val svc = detectionService
            if (svc == null) {
                Toast.makeText(this, "请在 QQ 进程内打开本页", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            statusText.text = "检测中…"
            btnRefresh.visibility = View.GONE
            refreshSpinner.start()
            svc.refreshAsync { outcome ->
                mainHandler.post {
                    btnRefresh.visibility = View.VISIBLE
                    refreshSpinner.stop()
                    val msg = when (outcome) {
                        is DetectionOutcome.BaselineCreated -> "已建立基线：${outcome.count} 人"
                        is DetectionOutcome.Checked -> "完成 ${outcome.previousCount}→${outcome.currentCount}，消失 ${outcome.removed.size} 人"
                        is DetectionOutcome.Failed -> "失败：${outcome.reason}"
                        is DetectionOutcome.Skipped -> "跳过：${outcome.reason}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    reloadUi()
                }
            }
        }
    }

    /** 定时间隔选择弹窗（复用 ArkSelector 的列表样式） */
    private fun showIntervalPopup() {
        val T = UiTheme
        val dp = { v: Float -> T.dp(this, v) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
        }
        container.addView(TextView(this).apply {
            text = "定时检查 · TIMER"
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
            setPadding(dp(T.SP_1), 0, dp(T.SP_1), dp(T.SP_2))
        })
        intervalLabels.forEachIndexed { idx, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
                isClickable = true
                background = T.ripple(T.SURFACE, T.RADIUS_NONE, this@DetectorActivity, T.RULE)
            }
            val cur = intervalValues[idx] == prefs.intervalMinutes
            row.addView(View(this).apply {
                setBackgroundColor(if (cur) T.SIGNAL else android.graphics.Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(3f), dp(20f)).apply { rightMargin = dp(T.SP_3) })
            row.addView(TextView(this).apply {
                text = item
                setTextColor(if (cur) T.SIGNAL else T.TEXT)
                textSize = T.TEXT_BODY_1
                typeface = if (cur) T.typefaceMedium() else android.graphics.Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                prefs.intervalMinutes = intervalValues[idx]
                btnTimer.text = "定时：" + item
                detectionService?.reschedulePeriodic()
                intervalPopup?.dismiss()
                Toast.makeText(this,
                    if (intervalValues[idx] == 0) "已关闭定时检查"
                    else "定时检查：每 $item", Toast.LENGTH_SHORT).show()
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(T.SP_1) })
        }
        val pw = android.widget.PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(T.shape(T.INK, 0f, this@DetectorActivity, T.RULE))
            elevation = dp(8f).toFloat()
            isOutsideTouchable = true
        }
        intervalPopup = pw
        pw.showAsDropDown(btnTimer, 0, dp(T.SP_1))
    }

    private var intervalPopup: android.widget.PopupWindow? = null

    private fun intervalLabel(minutes: Int): String =
        intervalLabels[intervalValues.indexOf(minutes).let { if (it >= 0) it else 0 }]

    /** 定时按钮可用态：仅在已建基线时高亮可设 */
    private fun updateTimerButtonState() {
        val owner = selectedOwner
        val acc = owner?.let { repository.getAccount(it) }
        val ready = acc?.baselineAt != null
        btnTimer.alpha = if (ready) 1f else 0.5f
    }

    private fun reloadUi() {
        val list = repository.listAccounts()
        accounts = list.map { it.ownerUin }.distinct()
        accountSelector.options(if (accounts.isEmpty()) listOf("暂无账号，请先刷新") else accounts,
            current = selectedOwner ?: accounts.firstOrNull())
        val want = selectedOwner ?: prefs.uiSelectedOwnerUin ?: accounts.firstOrNull()
        if (accounts.isNotEmpty()) {
            selectedOwner = accounts.firstOrNull { it == want } ?: accounts.first()
            accountSelector.setCurrent(selectedOwner!!)
        }
        refreshStatus()
        loadHistory()
        updateTimerButtonState()
    }

    private fun refreshStatus() {
        val T = UiTheme
        val owner = selectedOwner
        if (owner.isNullOrBlank()) {
            statusText.text = "尚无基线 · 登录 QQ 后点击立即刷新"
            statFriends.text = "—"; statDeleted.text = "0"; statSource.text = "—"
            return
        }
        val acc = repository.getAccount(owner)
        val snap = repository.getSnapshot(owner)
        val hist = repository.listHistory(owner)
        val fc = snap?.friends?.size
        statFriends.text = fc?.toString() ?: "—"
        statFriends.setTextColor(if ((fc ?: 0) > 10) T.STATE_OK else T.SIGNAL)
        statDeleted.text = hist.size.toString()
        statDeleted.setTextColor(if (hist.isNotEmpty()) T.DANGER else T.TEXT_2)
        val unread = repository.unreadCount(owner)
        statSource.text = if (unread > 0) "$unread UNREAD" else "OK"
        statSource.setTextColor(if (unread > 0) T.UNREAD else T.STATE_OK)
        statusText.text = buildString {
            append(owner)
            if (acc?.baselineAt != null) append("  ·  基线 ").append(timeFmtFull.format(Date(acc.baselineAt)))
            else append("  ·  未建基线")
            if (acc?.lastCheckAt != null) append("\n检测 ").append(timeFmtFull.format(Date(acc.lastCheckAt)))
            if (!acc?.lastError.isNullOrBlank()) append("\n⚠ ").append(acc?.lastError)
        }
    }

    private fun loadHistory() {
        val owner = selectedOwner
        if (owner.isNullOrBlank()) {
            historyAdapter.submitList(emptyList())
            histCount.text = "0"
            emptyView.visibility = View.VISIBLE
            return
        }
        val q = searchBox.text?.toString() ?: ""
        Thread {
            val all = repository.listHistory(owner)
            val filtered = all.filter { rec ->
                (currentFilter != 1 || !rec.read) &&
                    (q.isBlank() || rec.friendUin.contains(q, true) || rec.friendName.contains(q, true))
            }
            mainHandler.post {
                historyAdapter.submitList(filtered)
                histCount.text = "${filtered.size}"
                emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmClear() {
        val owner = selectedOwner ?: return
        ArkDialog.confirm(this, "CLEAR HISTORY", "清空账号 $owner 的被删记录？\n快照基线保留。", "清空") {
            repository.clearHistory(owner)
            refreshStatus(); loadHistory()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmReset() {
        val owner = selectedOwner ?: return
        ArkDialog.confirm(this, "RESET DATA", "将删除该账号的错误基线与被删记录。\n之后请重新点「立即刷新」。", "重置") {
            detectionService?.resetDirtyData(owner) ?: repository.resetAccountData(owner)
            Toast.makeText(this, "已重置，请再点立即刷新", Toast.LENGTH_SHORT).show()
            refreshStatus(); loadHistory()
        }
    }

    companion object {
        const val EXTRA_OWNER_UIN = "owner_uin"
    }
}
