package io.github.wxgroup.reborn

import android.app.Application
import io.github.wxgroup.reborn.core.GroupStore

/**
 * 模块 App 的 Application：首次启动时把默认签名（8.0.78 实测）与空分组写入本应用私有目录，
 * 并把文件 chmod 为其他用户可读，以便微信进程（绕过 AppsFilter）直接读。
 */
class WxGroupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GroupStore.ensureDefaults(this)
    }
}
