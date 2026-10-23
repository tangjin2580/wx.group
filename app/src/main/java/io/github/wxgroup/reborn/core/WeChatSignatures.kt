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
 * 微信签名配置：把「版本相关的」类/方法/字段名全部抽出来，运行时读取。
 * 这样换微信版本时无需改代码，只需改这份 JSON（见 assets / SignatureActivity）。
 *
 * @param revision 版本戳：内置签名升级时用它覆盖用户本地的旧副本。
 *   ⚠️ [toJson] 必须保留它 —— 早期实现序列化时丢掉 revision，导致 Provider
 *   回传给微信进程的签名 revision=0，永远被内置签名压制，用户在签名页的修改
 *   根本不生效。
 * @param fields 逻辑字段名 → 真实字段名。值可用逗号分隔表示多个候选（按顺序尝试），
 *   例如 "username" -> "field_username,field_talker"。由内置分析器产出，
 *   缺失时 Hook 侧回退到代码里的默认候选（见 ConversationGroupHook.fieldNames）。
 */
data class WeChatSignatures(
    val wechatVersion: String,
    val classes: Map<String, String>,
    val methods: Map<String, MethodDescriptor>,
    val fields: Map<String, String> = emptyMap(),
    val revision: Int = 0
) {
    /** 把 methods 里的 className key 解析成完整类名 */
    fun resolveClassName(keyOrName: String): String =
        classes[keyOrName] ?: keyOrName

    /**
     * 逻辑字段（"username"/"flag"/"unread"/"remark"/"nickname"）→ 候选真实字段名。
     * 签名里配的候选排在 [defaults] 前面；两边都有的名字只保留一次。
     */
    fun fieldNames(key: String, defaults: List<String>): List<String> {
        val fromSig = fields[key]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return (fromSig + defaults).distinct()
    }

    /** 单个字段名（取第一个候选） */
    fun fieldName(key: String, default: String): String =
        fieldNames(key, listOf(default)).firstOrNull() ?: default

    fun toJson(): JSONObject = JSONObject().apply {
        put("wechatVersion", wechatVersion)
        put("revision", revision)
        put("classes", JSONObject(classes))
        put("methods", JSONObject(methods.mapValues { it.value.toJson() }))
        if (fields.isNotEmpty()) put("fields", JSONObject(fields))
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
            val fields = root.optJSONObject("fields")?.let { obj ->
                buildMap {
                    obj.keys().forEach { k -> put(k, obj.getString(k)) }
                }
            } ?: emptyMap()
            return WeChatSignatures(
                wechatVersion = root.optString("wechatVersion", "unknown"),
                classes = classes,
                methods = methods,
                fields = fields,
                revision = root.optInt("revision", 0)
            )
        }
    }
}

private fun JSONArray.toStrList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until length()) list.add(getString(i))
    return list
}
