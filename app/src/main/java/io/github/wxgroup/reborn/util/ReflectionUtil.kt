package io.github.wxgroup.reborn.util

import io.github.wxgroup.reborn.xp.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Xposed 反射辅助：把签名 JSON 里的「友好类型名」转成 JVM 描述符 / Class，
 * 并提供按候选名取值的容错方法（微信字段名各版本不一致，需要多候选尝试）。
 */
object ReflectionUtil {

    /** 把 "java.lang.String" / "int" / "android.view.View" 转成 JVM 描述符 */
    fun toDescriptor(type: String): String {
        val t = type.trim()
        if (t.endsWith("[]")) return "[L${toDescriptor(t.removeSuffix("[]"))}"
        return when (t) {
            "void" -> "V"
            "int" -> "I"
            "boolean" -> "Z"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            else -> "L${t.replace('.', '/')};"
        }
    }

    fun resolveClass(loader: ClassLoader, type: String): Class<*>? {
        val t = type.trim()
        // 基本类型必须返回对应的 primitive Class，否则 findAndHookMethod 会因参数类型不匹配而失败
        return when (t) {
            "void" -> Void.TYPE
            "int" -> Int::class.javaPrimitiveType
            "boolean" -> Boolean::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            else -> try {
                XposedHelpers.findClass(t, loader)
            } catch (t2: Throwable) {
                null
            }
        }
    }

    fun resolveParamClasses(loader: ClassLoader, params: List<String>): Array<Class<*>?> =
        params.map { resolveClass(loader, it) }.toTypedArray()

    fun findClass(loader: ClassLoader, name: String): Class<*>? {
        // 签名里可能写成 "jo5/y0"（斜杠），findClass 需要二进制名（点分隔），统一转换
        val normalized = name.replace('/', '.')
        return try {
            XposedHelpers.findClass(normalized, loader)
        } catch (t: Throwable) {
            null
        }
    }

    /** 按顺序尝试候选字段名，返回第一个非空字段值 */
    fun getFieldValue(obj: Any?, candidates: List<String>): Any? {
        if (obj == null) return null
        for (name in candidates) {
            try {
                val f: Field = obj.javaClass.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (t: Throwable) {
                // 继续尝试下一个候选 / 父类
            }
        }
        // 退一步：在自身与父类里搜所有字段名包含候选子串的
        for (name in candidates) {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                c.declaredFields.forEach { f ->
                    if (f.name.contains(name, ignoreCase = true)) {
                        f.isAccessible = true
                        return f.get(obj)
                    }
                }
                c = c.superclass
            }
        }
        return null
    }

    /** 按候选方法名调用无参或单参方法 */
    fun callMethod(obj: Any?, candidates: List<String>, vararg args: Any?): Any? {
        if (obj == null) return null
        for (name in candidates) {
            try {
                val m: Method = obj.javaClass.declaredMethods.firstOrNull { it.name == name } ?: continue
                m.isAccessible = true
                return if (args.isEmpty()) m.invoke(obj) else m.invoke(obj, *args)
            } catch (t: Throwable) {
                // 继续
            }
        }
        return null
    }

    /** 取字符串字段（如会话 username） */
    fun getStringField(obj: Any?, candidates: List<String>): String? {
        val v = getFieldValue(obj, candidates) ?: return null
        return v.toString().takeIf { it.isNotBlank() }
    }
}
