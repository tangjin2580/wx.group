package io.github.wxgroup.reborn.xp

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 兼容旧 Xposed 的 `XposedHelpers` 子集：本项目只用了
 * findClass / getObjectField / callMethod / findAndHookMethod 四个。
 *
 * ⚠️ **性能关键**：会话列表的 getCount/getView 每帧都会走 getObjectField / callMethod，
 * 而 JDK 反射的「找字段 / 找方法」需要遍历继承链 + 遍历 declaredMethods（每次都新建数组），
 * 单帧能放大到上千次。这里把解析结果按「运行时类 + 名字」缓存起来，
 * 命中后只剩一次 Field.get / Method.invoke。未命中（字段/方法不存在）也缓存，
 * 避免对 `i1` 这种已经改名的旧候选反复做全类扫描。
 */
object XposedHelpers {

    /** 缓存里的「解析失败」占位（ConcurrentHashMap 不允许存 null） */
    private val NONE = Any()

    private val fieldCache = ConcurrentHashMap<String, Any>(512)
    private val methodCache = ConcurrentHashMap<String, Any>(512)

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
        val f = fieldOf(obj.javaClass, fieldName) ?: return null
        return try {
            f.get(obj)
        } catch (t: Throwable) {
            null
        }
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        obj ?: return
        val f = fieldOf(obj.javaClass, fieldName) ?: return
        try {
            f.set(obj, value)
        } catch (t: Throwable) {
        }
    }

    /** 按运行时类 + 字段名解析（沿父类链），结果缓存。 */
    fun fieldOf(clazz: Class<*>, fieldName: String): Field? {
        val key = clazz.name + '#' + fieldName
        val cached = fieldCache[key]
        if (cached != null) return if (cached === NONE) null else cached as Field
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                fieldCache[key] = f
                return f
            } catch (t: Throwable) {
                // 换父类继续找
            }
            c = c.superclass
        }
        fieldCache[key] = NONE
        return null
    }

    /** 某个类（含父类）有没有这个名字的无参方法 —— 只做一次解析，后续走缓存 */
    fun hasNoArgMethod(clazz: Class<*>, methodName: String): Boolean =
        methodOf(clazz, methodName, emptyArray()) != null

    /**
     * 按「方法名 + 参数个数」在类及父类里找，参数用「可赋值 + 基本类型↔包装类」匹配。
     * 微信的方法名各版本不同（a0/b0 这类），靠参数个数 + 类型定位。
     *
     * 解析结果按「类名 + 方法名 + 参数签名」缓存；参数签名取实参的运行时类名，
     * 所以同名同参数个数的不同重载不会互相污染。
     */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        obj ?: return null
        val m = methodOf(obj.javaClass, methodName, args) ?: return null
        return try {
            m.invoke(obj, *args)
        } catch (t: Throwable) {
            null
        }
    }

    private fun methodOf(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        val key = buildString(48) {
            append(clazz.name).append('#').append(methodName).append('#').append(args.size)
            for (a in args) append('#').append(a?.javaClass?.name ?: "null")
        }
        val cached = methodCache[key]
        if (cached != null) return if (cached === NONE) null else cached as Method
        val found = findMethodBestMatch(clazz, methodName, args)
        if (found != null) {
            found.isAccessible = true
            methodCache[key] = found
        } else {
            methodCache[key] = NONE
        }
        return found
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
