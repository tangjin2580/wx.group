package io.github.wxgroup.reborn.xp

import io.github.libxposed.api.XposedInterface

/**
 * 兼容旧 Xposed 的 `XC_MethodHook`：保留 `beforeHookedMethod` / `afterHookedMethod`
 * 两个回调与 `MethodHookParam` 的字段语义，内部用 libxposed 的 Chain 驱动。
 *
 * 语义对齐点（都按 classic Xposed 的行为实现）：
 *  - `beforeHookedMethod` 里改 `param.args` → 原方法用新参数调用；
 *  - `beforeHookedMethod` 里设 `param.result` → 跳过原方法，返回该值；
 *  - `beforeHookedMethod` 里调用 `param.invokeOriginal()` → 显式调用一次原方法，
 *    之后**不会**再自动调用原方法（避免原方法被调用两次）；
 *  - `afterHookedMethod` 能读到最终结果、也能覆盖 `param.result`。
 */
open class XC_MethodHook {

    /** 取消 hook 的句柄 */
    fun interface Unhook {
        fun unhook()
    }

    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    internal fun dispatch(chain: XposedInterface.Chain): Any? {
        val param = MethodHookParam(chain)
        try {
            beforeHookedMethod(param)
        } catch (t: Throwable) {
            XposedBridge.log("beforeHookedMethod 异常: ${t.javaClass.name}: ${t.message}", t)
        }

        var result: Any? = null
        var throwable: Throwable? = null
        try {
            result = when {
                param.resultSet -> param.result
                param.invokedOriginal -> param.originalResult
                else -> chain.proceed(param.args)
            }
        } catch (t: Throwable) {
            throwable = t
        }
        param.setResultQuietly(result)

        try {
            afterHookedMethod(param)
        } catch (t: Throwable) {
            XposedBridge.log("afterHookedMethod 异常: ${t.javaClass.name}: ${t.message}", t)
        }

        if (throwable != null) throw throwable
        return param.result
    }

    class MethodHookParam internal constructor(val chain: XposedInterface.Chain) {
        val thisObject: Any? get() = chain.getThisObject()
        val method: java.lang.reflect.Executable get() = chain.getExecutable()

        /** 参数数组，可写：改这里会传导给原方法 */
        var args: Array<Any?> = chain.getArgs().toTypedArray()

        private var resultValue: Any? = null
        internal var resultSet: Boolean = false
            private set

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                resultSet = true
            }

        internal var invokedOriginal: Boolean = false
            private set
        internal var originalResult: Any? = null
            private set

        internal fun setResultQuietly(value: Any?) {
            resultValue = value
        }

        /** 显式调用一次原方法（等价于经典 Xposed 的 XposedBridge.invokeOriginalMethod） */
        fun invokeOriginal(): Any? {
            invokedOriginal = true
            originalResult = chain.proceed(args)
            return originalResult
        }
    }
}
