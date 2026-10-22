package io.github.wxgroup.reborn.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WxSettings

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    private lateinit var swBadgeDot: SwitchCompat
    private lateinit var swHideEmpty: SwitchCompat
    private lateinit var swCompact: SwitchCompat
    private lateinit var swSenders: SwitchCompat

    /** 同步通道卡片的当前显示状态 —— 用来避免 onResume 反复重播同一段淡入淡出 */
    private var channelShown: Boolean? = null
    /** 首次进入不做可见性动画，否则会从「不可见」淡入一次，看着像闪一下 */
    private var channelAnimated = false

    private fun collect(): WxSettings = WxSettings(
        badgeDot = swBadgeDot.isChecked,
        hideEmptyGroups = swHideEmpty.isChecked,
        compactRows = swCompact.isChecked,
        showSenders = swSenders.isChecked
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val version = appVersion()
        findViewById<TextView>(R.id.tv_version).text = getString(R.string.settings_version, version)
        findViewById<TextView>(R.id.tv_about_desc).text = getString(R.string.settings_about_desc, version)

        // 系统开了「减少动效」→ 摘掉按下抬升的状态动画（时长令牌见 @integer/wg_motion_ms）
        if (Motion.reducedMotion(this)) {
            stripLift(R.id.btn_groups)
            stripLift(R.id.btn_signatures)
            stripLift(R.id.btn_fix_channel)
        }

        // ── 显示偏好开关 ──────────────────────────────────────────────
        // 写入后走「镜像 + Provider 通知」通道，微信里的 Hook 2s 内拿到新值，
        // 不用重启微信（轮询兜底，最坏也就 1.2s 一轮的检测节奏）。
        val s = GroupStore.getSettings(this)
        swBadgeDot = findViewById(R.id.sw_badge_dot)
        swHideEmpty = findViewById(R.id.sw_hide_empty)
        swCompact = findViewById(R.id.sw_compact)
        swSenders = findViewById(R.id.sw_senders)
        swBadgeDot.isChecked = s.badgeDot
        swHideEmpty.isChecked = s.hideEmptyGroups
        swCompact.isChecked = s.compactRows
        swSenders.isChecked = s.showSenders
        val persist = { GroupStore.putSettings(this, collect()) }
        swBadgeDot.setOnCheckedChangeListener { _, _ -> persist() }
        swHideEmpty.setOnCheckedChangeListener { _, _ -> persist() }
        swCompact.setOnCheckedChangeListener { _, _ -> persist() }
        swSenders.setOnCheckedChangeListener { _, _ -> persist() }

        // 整行可点：点标题/说明区域也能切换开关（开关本身仍是独立控件，照常响应）
        bindRow(R.id.row_badge_dot, swBadgeDot)
        bindRow(R.id.row_hide_empty, swHideEmpty)
        bindRow(R.id.row_compact, swCompact)
        bindRow(R.id.row_senders, swSenders)

        findViewById<View>(R.id.btn_groups).setOnClickListener {
            startActivity(Intent(this, GroupManageActivity::class.java))
        }
        findViewById<View>(R.id.btn_signatures).setOnClickListener {
            startActivity(Intent(this, SignatureActivity::class.java))
        }

        // ── 分组备份 ─────────────────────────────────────────────────
        findViewById<View>(R.id.btn_export).setOnClickListener { exportGroups() }
        findViewById<View>(R.id.btn_import).setOnClickListener { importGroupsDialog() }

        findViewById<View>(R.id.btn_kill_wechat).setOnClickListener {
            confirmKillWeChat()
        }
        findViewById<Button>(R.id.btn_fix_channel).setOnClickListener { provisionChannel() }
        findViewById<View>(R.id.btn_about).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_about)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /**
     * 版本号从 PackageManager 读 —— 不开 buildConfig 特性（AGP 8 起默认关闭），
     * 免得为了显示一行字去改构建配置。
     */
    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (t: Throwable) {
        ""
    }

    /** 去掉按下抬升动画（减少动效模式） */
    private fun stripLift(id: Int) {
        findViewById<View>(id).stateListAnimator = null
    }

    /** 设置项整行点击 = 切换开关 */
    private fun bindRow(rowId: Int, sw: SwitchCompat) {
        findViewById<View>(rowId).setOnClickListener { sw.toggle() }
    }

    override fun onResume() {
        super.onResume()
        val first = !channelAnimated
        refreshChannelState()
        if (first) channelAnimated = true
    }

    /** 镜像文件不存在时，App 里的改动到不了微信 —— 显式提示，而不是只在日志里失败 */
    private fun refreshChannelState() {
        val ready = GroupStore.mirrorsReady()
        val show = !ready
        if (channelShown == show) return       // 状态没变就别重播动画
        channelShown = show

        val vis = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_channel_warn).visibility = vis
        findViewById<View>(R.id.btn_fix_channel).visibility = vis
        toggleCard(findViewById(R.id.card_channel), show)
    }

    /** 卡片显示/隐藏：200ms 淡入淡出；减少动效或首次进入时直接切换 */
    private fun toggleCard(card: View, visible: Boolean) {
        card.animate().cancel()
        if (!channelAnimated || Motion.reducedMotion(this)) {
            card.alpha = 1f
            card.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            card.alpha = 0f
            card.visibility = View.VISIBLE
            card.animate()
                .alpha(1f)
                .setDuration(Motion.DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            card.animate()
                .alpha(0f)
                .setDuration(Motion.DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { card.visibility = View.GONE }
                .start()
        }
    }

    private fun provisionChannel() {
        Thread {
            val ok = GroupStore.provisionMirrorsWithRoot()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // putSettings 顺带发变更通知，微信收到后会用自己那份分组填上空镜像
                if (ok) GroupStore.putSettings(this, collect())
                refreshChannelState()
                Toast.makeText(
                    this,
                    if (ok) R.string.settings_channel_fixed else R.string.settings_channel_fix_fail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────
    // 导出：分组 JSON → 剪贴板（顺手展示预览，方便直接全选复制别的工具里）
    // 导入：粘贴 JSON → 整表替换。重装微信 / 换机时靠这对操作救回分组。
    // ─────────────────────────────────────────────────────────────
    private fun exportGroups() {
        try {
            val json = GroupStore.exportGroupsJson(this)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("wxgroup_backup", json))
            val n = GroupStore.getGroups(this).size
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_export)
                .setMessage(getString(R.string.settings_export_done, n) + "\n\n" +
                    json.take(400) + if (json.length > 400) "\n…（已完整复制）" else "")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.settings_export_fail, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun importGroupsDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_import_hint)
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import)
            .setMessage(getString(R.string.settings_import_hint))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.settings_import_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val n = GroupStore.importGroupsJson(this, text)
                val msg = if (n >= 0) getString(R.string.settings_import_ok, n) else getString(R.string.settings_import_bad)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 强制停止微信。改完签名/分组后重启微信才生效，这个按钮就是干这个的。
     *
     * `am force-stop` 需要系统级 FORCE_STOP_PACKAGES 权限（普通 App 拿不到），
     * 所以 root 设备上走 `su` 提权；没 root 时退化为 killBackgroundProcesses
     * （只对后台进程有效，前台微信杀不掉，所以这里会如实提示）。
     */
    private fun confirmKillWeChat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_kill_wechat)
            .setMessage(R.string.settings_kill_wechat_msg)
            .setPositiveButton(R.string.settings_kill_wechat_confirm) { _, _ ->
                // su 要等用户在 root 管理器里授权，放 UI 线程会直接 ANR
                Thread {
                    val killed = forceStopWeChat()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val msg = if (killed) R.string.settings_kill_wechat_done else R.string.settings_kill_wechat_fail
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun forceStopWeChat(): Boolean {
        val killedBySu = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $WECHAT_PACKAGE"))
            p.outputStream.close()
            p.waitFor()
            p.exitValue() == 0
        } catch (t: Throwable) {
            false
        }
        // 兜底：即便没有 root，也尝试按后台进程方式杀掉（对前台微信无效，但无害）
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        try {
            @Suppress("DEPRECATION")
            am?.killBackgroundProcesses(WECHAT_PACKAGE)
        } catch (_: Throwable) {
        }
        return killedBySu
    }
}
