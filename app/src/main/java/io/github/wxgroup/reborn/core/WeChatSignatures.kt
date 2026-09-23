package io.github.wxgroup.reborn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 微信方法签名描述。
 * @param className 可以是 classes 映射里的 key（如 "ConversationUI"），也可以是完整类名
 * @param methodName 方法名；构造方法用 "<init>"；为空或含 "TODO" 表示该签名尚未填写，会被跳过
 * @param params JVM 描述符或完整类名列表，例如 ["int"]、["android.view.View"]
 * @param returnType 返回类型描述符/类名
 */
data class MethodDescriptor(
    val className: String,
    val methodName: String,
    val params: List<String> = emptyList(),
    val returnType: String = "void"
) {
    val isValid: Boolean
        get() = methodName.isNotBlank() && !methodName.contains("TODO", ignoreCase = true)

    fun toJson(): JSONObject = JSONObject().apply {
        put("class", className)
        put("methodName", methodName)
        put("params", JSONArray(params))
        put("ret", returnType)
    }

    companion object {
        fun fromJson(o: JSONObject): MethodDescriptor = MethodDescriptor(
            className = o.optString("class", ""),
            methodName = o.optString("methodName", o.optString("name", "")),
            params = o.optJSONArray("params")?.toStrList() ?: emptyList(),
            returnType = o.optString("ret", "void")
        )
    }
}

/**
 * 微信签名配置：把「版本相关的」类/方法签名全部抽出来，运行时读取。
 * 这样换微信版本时无需改代码，只需改这份 JSON（见 assets / SignatureActivity）。
 */
data class WeChatSignatures(
    val wechatVersion: String,
    val classes: Map<String, String>,
    val methods: Map<String, MethodDescriptor>
) {
    /** 把 methods 里的 className key 解析成完整类名 */
    fun resolveClassName(keyOrName: String): String =
        classes[keyOrName] ?: keyOrName

    fun toJson(): JSONObject = JSONObject().apply {
        put("wechatVersion", wechatVersion)
        put("classes", JSONObject(classes))
        put("methods", JSONObject(methods.mapValues { it.value.toJson() }))
    }

    companion object {
        fun fromJson(root: JSONObject): WeChatSignatures {
            val classes = root.optJSONObject("classes")?.let { obj ->
                buildMap {
                    obj.keys().forEach { k -> put(k, obj.getString(k)) }
                }
            } ?: emptyMap()
            val methods = root.optJSONObject("methods")?.let { obj ->
                buildMap {
                    obj.keys().forEach { k ->
                        put(k, MethodDescriptor.fromJson(obj.getJSONObject(k)))
                    }
                }
            } ?: emptyMap()
            return WeChatSignatures(
                wechatVersion = root.optString("wechatVersion", "unknown"),
                classes = classes,
                methods = methods
            )
        }
    }
}

private fun JSONArray.toStrList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until length()) list.add(getString(i))
    return list
}
