package io.github.wxgroup.reborn

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.GroupTheme

/**
 * 模块 App 的 Application：首次启动时把默认签名（8.0.78 实测）与空分组写入本应用私有目录，
 * 并把文件 chmod 为其他用户可读，以便微信进程（绕过 AppsFilter）直接读。
 *
 * 另外在冷启动时把「主题里的深浅色模式」交给 AppCompat —— 用户选了强制深色，
 * 下次打开模块 App 就应该是深色（微信里那几行收纳组则由 Hook 自己按 darkMode 取色）。
 */
class WxGroupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GroupStore.ensureDefaults(this)
        applyThemeMode()
    }

    private fun applyThemeMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (GroupStore.getSettings(this).darkMode) {
                GroupTheme.Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                GroupTheme.Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
