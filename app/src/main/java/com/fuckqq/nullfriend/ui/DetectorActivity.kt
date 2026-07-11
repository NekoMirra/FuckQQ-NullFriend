package com.fuckqq.nullfriend.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fuckqq.nullfriend.BuildConfig
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.R
import com.fuckqq.nullfriend.data.DetectorRepository
import com.fuckqq.nullfriend.data.Prefs
import com.fuckqq.nullfriend.domain.DetectionOutcome
import com.fuckqq.nullfriend.domain.DeletionRecord
import com.fuckqq.nullfriend.service.ChatLauncher
import com.fuckqq.nullfriend.service.DetectionService
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetectorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repository: DetectorRepository
    private var detectionService: DetectionService? = null
    private var injectedMode = false

    private lateinit var statusText: TextView
    private lateinit var accountSpinner: Spinner
    private lateinit var historyList: ListView
    private lateinit var switchNotify: SwitchMaterial
    private lateinit var intervalSpinner: Spinner

    private var accounts: List<String> = emptyList()
    private var history: List<DeletionRecord> = emptyList()
    private var selectedOwner: String? = null

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val intervalLabels = listOf("关闭", "30 分钟", "1 小时", "3 小时")
    private val intervalValues = listOf(0, 30, 60, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detector)
        title = getString(R.string.app_name)

        statusText = findViewById(R.id.statusText)
        accountSpinner = findViewById(R.id.accountSpinner)
        historyList = findViewById(R.id.historyList)
        switchNotify = findViewById(R.id.switchNotify)
        intervalSpinner = findViewById(R.id.intervalSpinner)

        bindServices()

        val about = findViewById<TextView>(R.id.aboutText)
        about.text = buildString {
            appendLine("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("包名作用域: 仅 ${Constants.QQ_PACKAGE}")
            appendLine("仓库: FuckQQ-NullFriend")
            if (!injectedMode) {
                appendLine("当前为模块进程：检测请从 QQ 设置页橙色入口打开。")
            }
        }

        switchNotify.isChecked = prefs.notifyEnabled
        switchNotify.setOnCheckedChangeListener { _, checked ->
            prefs.notifyEnabled = checked
        }

        intervalSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalLabels)
        val idx = intervalValues.indexOf(prefs.intervalMinutes).let { if (it >= 0) it else 0 }
        intervalSpinner.setSelection(idx)
        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.intervalMinutes = intervalValues[position]
                detectionService?.reschedulePeriodic()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { doRefresh() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }

        historyList.setOnItemClickListener { _, _, position, _ ->
            val item = history.getOrNull(position) ?: return@setOnItemClickListener
            repository.markRead(item.id)
            AlertDialog.Builder(this)
                .setTitle(item.friendName)
                .setMessage(
                    "QQ: ${item.friendUin}\n" +
                        "约 ${timeFmt.format(Date(item.detectedAt))} 检测到\n" +
                        "来源: ${item.checkSource}\n\n${item.note}"
                )
                .setPositiveButton("打开聊天") { _, _ ->
                    ChatLauncher.openChat(this, item.friendUin, ModuleMain.classLoader)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
        historyList.setOnItemLongClickListener { _, _, position, _ ->
            val item = history.getOrNull(position) ?: return@setOnItemLongClickListener true
            ChatLauncher.openChat(this, item.friendUin, ModuleMain.classLoader)
            true
        }

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
        } catch (_: Throwable) {
        }
        prefs = Prefs(this)
        repository = DetectorRepository(this)
        detectionService = null
        injectedMode = false
        statusText.text =
            "模块未注入 QQ 进程：仅可浏览本进程数据。请从 QQ 内入口打开，并确认 LSPosed 仅勾选 QQ。"
    }

    private fun reloadUi() {
        val list = repository.listAccounts()
        accounts = list.map { it.ownerUin }.distinct()
        val labels = if (accounts.isEmpty()) listOf("（暂无账号，请先刷新）") else accounts
        accountSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val want = selectedOwner ?: prefs.uiSelectedOwnerUin ?: accounts.firstOrNull()
        val pos = accounts.indexOf(want).let { if (it >= 0) it else 0 }
        if (accounts.isNotEmpty()) {
            accountSpinner.setSelection(pos)
            selectedOwner = accounts[pos]
        }
        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (accounts.isEmpty()) return
                selectedOwner = accounts[position]
                prefs.uiSelectedOwnerUin = selectedOwner
                loadHistoryAndStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        loadHistoryAndStatus()
    }

    private fun loadHistoryAndStatus() {
        val owner = selectedOwner
        if (owner.isNullOrBlank()) {
            statusText.text = "状态：尚无基线。在 QQ 登录后点击立即刷新。"
            history = emptyList()
            historyList.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("暂无记录"))
            return
        }
        val acc = repository.getAccount(owner)
        val snap = repository.getSnapshot(owner)
        statusText.text = buildString {
            append("账号: $owner\n")
            if (acc?.baselineAt != null) {
                append("基线: ${timeFmt.format(Date(acc.baselineAt))}\n")
            } else {
                append("基线: 未建立\n")
            }
            if (acc?.lastCheckAt != null) {
                append("上次检测: ${timeFmt.format(Date(acc.lastCheckAt))}\n")
            }
            append("上次来源: ${acc?.lastSource ?: "—"}\n")
            append("好友数: ${snap?.friends?.size ?: "—"}\n")
            if (!acc?.lastError.isNullOrBlank()) {
                append("错误: ${acc?.lastError}\n")
            }
            append("作用域: 仅 ${Constants.QQ_PACKAGE}")
        }
        history = repository.listHistory(owner)
        val lines = if (history.isEmpty()) {
            listOf("暂无被删记录")
        } else {
            history.map {
                val mark = if (it.read) "" else "• "
                "$mark${it.friendName} (${it.friendUin})\n约 ${timeFmt.format(Date(it.detectedAt))} 检测到"
            }
        }
        historyList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    private fun doRefresh() {
        val svc = detectionService
        if (svc == null) {
            Toast.makeText(this, "请在 QQ 进程内打开本页（设置页橙色入口）", Toast.LENGTH_LONG).show()
            return
        }
        statusText.text = "检测中…"
        svc.refreshAsync { outcome ->
            val msg = when (outcome) {
                is DetectionOutcome.BaselineCreated ->
                    "已建立基线：${outcome.count} 人（${outcome.source}）"
                is DetectionOutcome.Checked ->
                    "完成：${outcome.previousCount}→${outcome.currentCount}，消失 ${outcome.removed.size} 人"
                is DetectionOutcome.Failed -> "失败：${outcome.reason}"
                is DetectionOutcome.Skipped -> "跳过：${outcome.reason}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            reloadUi()
        }
    }

    private fun confirmClear() {
        val owner = selectedOwner
        if (owner.isNullOrBlank()) return
        AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("清空账号 $owner 的被删记录？快照基线保留。")
            .setPositiveButton("清空") { _, _ ->
                repository.clearHistory(owner)
                loadHistoryAndStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val EXTRA_OWNER_UIN = "owner_uin"
    }
}
