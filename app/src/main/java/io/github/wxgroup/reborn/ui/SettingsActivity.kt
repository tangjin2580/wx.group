package io.github.wxgroup.reborn.ui

import android.app.ActivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import io.github.wxgroup.reborn.R

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btn_groups).setOnClickListener {
            startActivity(Intent(this, GroupManageActivity::class.java))
        }
        findViewById<Button>(R.id.btn_signatures).setOnClickListener {
            startActivity(Intent(this, SignatureActivity::class.java))
        }
        findViewById<Button>(R.id.btn_kill_wechat).setOnClickListener {
            confirmKillWeChat()
        }
        findViewById<Button>(R.id.btn_about).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_about)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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
            .setPositiveButton(R.string.settings_kill_wechat_confirm) { _, _ -> forceStopWeChat() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun forceStopWeChat() {
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
        val msg = if (killedBySu) getString(R.string.settings_kill_wechat_done)
        else getString(R.string.settings_kill_wechat_fail)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}

