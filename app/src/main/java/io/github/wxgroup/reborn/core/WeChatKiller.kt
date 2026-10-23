package io.github.wxgroup.reborn.core

import android.app.ActivityManager
import android.content.Context

/**
 * 强制停止微信。
 *
 * 改完签名/分组后重启微信才生效，这个能力就是干这个的。
 *
 * `am force-stop` 需要系统级 FORCE_STOP_PACKAGES 权限（普通 App 拿不到），
 * 所以 root 设备上走 `su` 提权；没 root 时退化为 killBackgroundProcesses
 * （只对后台进程有效，前台微信杀不掉，调用方需要如实提示）。
 *
 * 旧版 SettingsActivity 与新版 Compose 设置页共用这一个实现。
 */
object WeChatKiller {

    const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * 尝试强杀微信。**不要在 UI 线程调用**（`su` 要等 root 管理器授权，会 ANR）。
     *
     * @return true = su 提权成功杀掉了进程；false = 提权失败（已尝试后台兜底）
     */
    fun forceStop(context: Context): Boolean {
        val killedBySu = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $WECHAT_PACKAGE"))
            p.outputStream.close()
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
        // 兜底：即便没有 root，也尝试按后台进程方式杀掉（对前台微信无效，但无害）
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        try {
            @Suppress("DEPRECATION")
            am?.killBackgroundProcesses(WECHAT_PACKAGE)
        } catch (_: Throwable) {
        }
        return killedBySu
    }
}
