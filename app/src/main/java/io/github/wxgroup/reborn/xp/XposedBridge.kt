package io.github.wxgroup.reborn.xp

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.wxgroup.reborn.core.RunLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 薄封装：让旧的 `XposedBridge.hookMethod(...)` 调用在 libxposed 102 下继续可用。
 *
 * 为什么这么做：本项目从 legacy Xposed（de.robv.android.xposed:api:82）迁移到
 * libxposed 102（io.github.libxposed:api:102.0.0）。libxposed 的 hook 模型是
 * `hook(method).intercept(Hooker { intercept(chain) })`，和旧的 `XC_MethodHook`
 * 的 before/after 回调不同。这里只适配底层模型，尽量不动上层业务代码。
 *
 * 日志统一走 android.util.Log（tag=WxGroupReborn），既能在 adb logcat 里看到，
 * 又不依赖运行期是否注入了 legacy 的 XposedBridge（宿主进程里 libxposed 不保证有它）。
 */
object XposedBridge {

    private const val TAG = "WxGroupReborn"

    @Volatile
    private var xposedInterface: XposedInterface? = null

    /** 由模块入口（XposedModule）注入；只应在被 hook 的宿主进程里调用 */
    fun attach(iface: XposedInterface) {
        xposedInterface = iface
        // 进入 Hook 进程：日志开始进环形缓冲并落盘到 /data/local/tmp 镜像（模块 App 可读）
        RunLog.enableRemote()
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        RunLog.record("I", msg)
    }

    fun log(t: Throwable) {
        Log.e(TAG, "exception", t)
        RunLog.record("E", "exception: ${Log.getStackTraceString(t)}")
    }

    fun log(msg: String, t: Throwable) {
        Log.e(TAG, msg, t)
        RunLog.record("E", "$msg: ${Log.getStackTraceString(t)}")
    }

    fun hookMethod(member: java.lang.reflect.Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val iface = xposedInterface
            ?: throw IllegalStateException("XposedBridge 未 attach（宿主进程里应由模块入口先 attach）")
        val exec: java.lang.reflect.Executable = when (member) {
            is Method -> member
            is Constructor<*> -> member
            else -> throw IllegalArgumentException("不可 hook 的成员: $member")
        }
        exec.isAccessible = true
        val handle: XposedInterface.HookHandle =
            iface.hook(exec).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = callback.dispatch(chain)
            })
        return XC_MethodHook.Unhook { handle.unhook() }
    }
}
