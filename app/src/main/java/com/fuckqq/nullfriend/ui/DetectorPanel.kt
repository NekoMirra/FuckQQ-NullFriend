package com.fuckqq.nullfriend.ui

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
 * In-process UI (runs inside QQ). Does not depend on module Activity/resources.
 */
object DetectorPanel {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val intervalLabels = listOf("关闭", "30 分钟", "1 小时", "3 小时")
    private val intervalValues = listOf(0, 30, 60, 180)

    @Volatile
    private var openDialog: AlertDialog? = null

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
                val content = buildContent(activity)
                openDialog = AlertDialog.Builder(activity)
                    .setTitle("去TM的单向好友")
                    .setView(content)
                    .setNegativeButton("关闭") { d, _ -> d.dismiss() }
                    .setOnDismissListener { openDialog = null }
                    .create()
                openDialog?.show()
            } catch (t: Throwable) {
                Log.e("DetectorPanel.show failed", t)
                Toast.makeText(activity, "打开面板失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun buildContent(activity: Activity): View {
        val prefs = ModuleMain.prefs
        val repo = ModuleMain.repository
        val service = ModuleMain.detectionService

        val root = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 16))
        }
        root.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val status = TextView(activity).apply {
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }
        col.addView(status)

        val accountSpinner = Spinner(activity)
        col.addView(accountSpinner)

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

        val historyList = ListView(activity).apply {
            dividerHeight = 1
        }
        val historyLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 260)
        )
        col.addView(historyList, historyLp)

        fun refreshStatusAndList() {
            accounts = repo.listAccounts().map { it.ownerUin }.distinct().toMutableList()
            if (selected != null && selected !in accounts && accounts.isNotEmpty()) {
                // keep
            }
            if (selected == null) selected = accounts.firstOrNull()
            bindAccounts()
            val owner = selected
            if (owner.isNullOrBlank()) {
                status.text = "状态：尚无基线。登录 QQ 后点「立即刷新」。\n作用域: ${Constants.QQ_PACKAGE}"
                history = emptyList()
                historyList.adapter =
                    ArrayAdapter(activity, android.R.layout.simple_list_item_1, listOf("暂无记录"))
                return
            }
            val acc = repo.getAccount(owner)
            val snap = repo.getSnapshot(owner)
            status.text = buildString {
                append("账号: $owner\n")
                append(
                    if (acc?.baselineAt != null) "基线: ${timeFmt.format(Date(acc.baselineAt))}\n"
                    else "基线: 未建立\n"
                )
                if (acc?.lastCheckAt != null) {
                    append("上次: ${timeFmt.format(Date(acc.lastCheckAt))} · ${acc.lastSource ?: "—"}\n")
                }
                append("好友数: ${snap?.friends?.size ?: "—"}\n")
                if (!acc?.lastError.isNullOrBlank()) append("错误: ${acc?.lastError}\n")
                append("v${BuildConfig.VERSION_NAME} · 仅 ${Constants.QQ_PACKAGE}")
            }
            history = repo.listHistory(owner)
            val lines = if (history.isEmpty()) listOf("暂无被删记录（长按可打开聊天）")
            else history.map {
                val mark = if (it.read) "" else "• "
                "$mark${it.friendName} (${it.friendUin})\n约 ${timeFmt.format(Date(it.detectedAt))}"
            }
            historyList.adapter =
                ArrayAdapter(activity, android.R.layout.simple_list_item_1, lines)
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

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRefresh = Button(activity).apply { text = "立即刷新" }
        val btnClear = Button(activity).apply { text = "清空历史" }
        btnRow.addView(btnRefresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btnRow)

        val swNotify = Switch(activity).apply {
            text = "系统通知（默认关）"
            isChecked = prefs.notifyEnabled
            setOnCheckedChangeListener { _, c -> prefs.notifyEnabled = c }
        }
        col.addView(swNotify)

        col.addView(TextView(activity).apply { text = "定时检测"; setPadding(0, dp(activity, 8), 0, 0) })
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
        col.addView(intervalSpinner)

        col.addView(TextView(activity).apply {
            text = "若好友数为 0：先打开 QQ「联系人」滑一遍，等几秒再点立即刷新。\n" +
                "列表中消失可能是对方删除、自己删除或其他变化，无法区分。\n点条目看详情；长按打开聊天。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(activity, 8), 0, dp(activity, 4))
        })

        // Move list below disclaimer for better layout: already added above; ok for v0.1.1

        btnRefresh.setOnClickListener {
            status.text = "检测中…"
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
                    // reload accounts after baseline
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
                .setMessage("清空 $owner 的被删记录？基线保留。")
                .setPositiveButton("清空") { _, _ ->
                    repo.clearHistory(owner)
                    refreshStatusAndList()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        historyList.setOnItemClickListener { _, _, position, _ ->
            val item = history.getOrNull(position) ?: return@setOnItemClickListener
            repo.markRead(item.id)
            AlertDialog.Builder(activity)
                .setTitle(item.friendName)
                .setMessage(
                    "QQ: ${item.friendUin}\n约 ${timeFmt.format(Date(item.detectedAt))}\n${item.note}"
                )
                .setPositiveButton("打开聊天") { _, _ ->
                    ChatLauncher.openChat(activity, item.friendUin, ModuleMain.classLoader)
                }
                .setNegativeButton("关闭", null)
                .show()
            refreshStatusAndList()
        }
        historyList.setOnItemLongClickListener { _, _, position, _ ->
            val item = history.getOrNull(position) ?: return@setOnItemLongClickListener true
            ChatLauncher.openChat(activity, item.friendUin, ModuleMain.classLoader)
            true
        }

        refreshStatusAndList()
        return root
    }

    /**
     * Floating entry button attached to activity decor.
     */
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
                    setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                    background = GradientDrawable().apply {
                        setColor(0xE0FF5722.toInt())
                        cornerRadius = dp(activity, 24).toFloat()
                    }
                    elevation = dp(activity, 6).toFloat()
                    setOnClickListener { show(activity) }
                }
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = dp(activity, 16)
                    bottomMargin = dp(activity, 96)
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
