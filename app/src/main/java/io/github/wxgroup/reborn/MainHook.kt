package io.github.wxgroup.reborn

import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.wxgroup.reborn.hook.ConversationGroupHook
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * libxposed 102 模块入口（替代旧的 XposedInit / IXposedHookLoadPackage）。
 *
 * 完全离线、无服务端校验。仅对微信主进程生效；在 Application.attach 时拿到 Context，
 * 再初始化分组 Hook。
 *
 * 入口通过 `META-INF/xposed/java_init.list` 声明（不再是 assets/xposed_init）。
 */
class MainHook : XposedModule() {

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != WECHAT_PACKAGE) return

        // 把框架接口交给薄封装，供 XposedBridge.hookMethod 使用
        XposedBridge.attach(this)

        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        XposedBridge.log("微信已加载，准备初始化分组 Hook")
                        ConversationGroupHook().setup(context)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("hook Application.attach 失败: ${t.message}")
        }
    }
}
