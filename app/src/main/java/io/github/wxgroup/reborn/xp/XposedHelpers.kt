package io.github.wxgroup.reborn.xp

import java.lang.reflect.Method

/**
 * 兼容旧 Xposed 的 `XposedHelpers` 子集：本项目只用了
 * findClass / getObjectField / callMethod / findAndHookMethod 四个。
 */
object XposedHelpers {

    fun findClass(name: String, classLoader: ClassLoader): Class<*>? {
        // 签名里常写成 "jo5/y0"（斜杠），Class.forName 需要点分隔的二进制名
        val normalized = name.replace('/', '.')
        return try {
            Class.forName(normalized, false, classLoader)
        } catch (t: Throwable) {
            null
        }
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        obj ?: return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (t: Throwable) {
                // 换父类继续找
            }
            c = c.superclass
        }
        return null
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        obj ?: return
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (t: Throwable) {
            }
            c = c.superclass
        }
    }

    /**
     * 按「方法名 + 参数个数」在类及父类里找，参数用「可赋值 + 基本类型↔包装类」匹配。
     * 微信的方法名各版本不同（a0/b0 这类），靠参数个数 + 类型定位。
     */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        obj ?: return null
        val m = findMethodBestMatch(obj.javaClass, methodName, args) ?: return null
        m.isAccessible = true
        return try {
            m.invoke(obj, *args)
        } catch (t: Throwable) {
            null
        }
    }

    /** 兼容旧签名：最后一个参数是 XC_MethodHook，前面的都是参数类型 Class */
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook? {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook ?: return null
        val paramTypes = ArrayList<Class<*>>()
        for (i in 0 until parameterTypesAndCallback.size - 1) {
            val t = parameterTypesAndCallback[i] as? Class<*> ?: return null
            paramTypes.add(t)
        }
        val m = findMethodExact(clazz, methodName, paramTypes) ?: return null
        m.isAccessible = true
        return try {
            XposedBridge.hookMethod(m, callback)
        } catch (t: Throwable) {
            null
        }
    }

    private fun findMethodExact(clazz: Class<*>, name: String, paramTypes: List<Class<*>>): Method? {
        val arr = paramTypes.toTypedArray()
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *arr)
            } catch (t: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    private fun findMethodBestMatch(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        var c: Class<*>? = clazz
        val candidates = ArrayList<Method>()
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == args.size) candidates.add(m)
            }
            c = c.superclass
        }
        for (m in candidates) {
            var ok = true
            val types = m.parameterTypes
            for (i in args.indices) {
                if (!argMatches(types[i], args[i])) {
                    ok = false
                    break
                }
            }
            if (ok) return m
        }
        return null
    }

    private fun argMatches(paramType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !paramType.isPrimitive
        val argClass = arg.javaClass
        if (paramType.isAssignableFrom(argClass)) return true
        // 基本类型 ↔ 包装类
        val boxed = when (paramType) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> null
        }
        return boxed != null && boxed.isAssignableFrom(argClass)
    }
}
