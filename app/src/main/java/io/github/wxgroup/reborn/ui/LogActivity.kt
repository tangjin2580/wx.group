package io.github.wxgroup.reborn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.RunLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志查看页。
 *
 * 数据源：/data/local/tmp/wxgroup_reborn_log.json —— 微信进程里的 Hook 日志
 * 经 RunLog 环形缓冲落盘到该镜像，本页只读。排查「签名失配 / Hook 没挂上」
 * 不再需要连 adb：装好模块 → 打开微信会话列表 → 回这里刷新。
 *
 * 操作：
 *   刷新 —— 重读镜像（微信侧落盘有 ~800ms 去抖，刚看过一遍就等一两秒再刷）
 *   复制 —— 全文进剪贴板，方便贴到 Issue
 *   清空 —— 写空镜像并盖 clearedAt，微信侧下次落盘会同步清掉内存缓冲
 */
class LogActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        title = getString(R.string.log_title)

        tvStatus = findViewById(R.id.tv_log_status)
        tvLog = findViewById(R.id.tv_log)

        findViewById<Button>(R.id.btn_log_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_log_copy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.btn_log_clear).setOnClickListener { clear() }

        refresh()
    }

    private fun refresh() {
        val snap = RunLog.readMirror()
        if (snap == null) {
            // 镜像不存在：给出可复制的预创建命令（微信进程写不了新文件，这是权限现实）
            tvStatus.text = getString(R.string.log_status_missing, RunLog.provisionHint())
            tvLog.text = getString(R.string.log_empty)
            return
        }
        val savedAt = if (snap.savedAt > 0) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snap.savedAt))
        } else "-"
        tvStatus.text = getString(R.string.log_status_ok, snap.lines.size, savedAt)
        tvLog.text = if (snap.lines.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            snap.lines.joinToString("\n")
        }
    }

    private fun copyAll() {
        val text = tvLog.text?.toString().orEmpty()
        if (text.isEmpty()) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("wxgroup_log", text))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun clear() {
        val ok = RunLog.clearMirror()
        Toast.makeText(
            this,
            if (ok) R.string.log_cleared else R.string.log_clear_fail,
            Toast.LENGTH_SHORT
        ).show()
        if (ok) refresh()
    }
}
