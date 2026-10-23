package io.github.wxgroup.reborn.hook

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.wxgroup.reborn.core.WeChatSignatures
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.util.ReflectionUtil
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers
import java.lang.reflect.Field

/**
 * ListView 分支（8.0.78/8.0.79 本机实际生效）。
 *
 * 适配器的 getView 直接按 position 索引自己的行列表，所以「虚位重映射」只要改写 args[0]
 * 就能让微信渲染正确的会话。这里负责：适配器 hook、虚拟模型构建、未读数统计。
 *
 * ── 性能约定（都是踩过帧率坑之后的写法，别改回去）──
 *   · 反射一律走 XposedHelpers 的缓存（Field/Method 解析只做一次）
 *   · talker 方法名、行容器字段名按类记住，不做「先试 f1 再试 i1」的二次扫描
 *   · getCount/getView 里不做字符串拼接比较、不做全表扫描（未读走 400ms 快照）
 *   · 分组未读 = 快照里查表求和，绝不每渲染一个组行就重扫整个会话列表
 */

// ==================================================================================
// 安装
// ==================================================================================
internal fun ConversationGroupHook.installListViewPath(loader: ClassLoader, sig: WeChatSignatures) {
    val className = sig.resolveClassName("ConversationAdapter")
    val clazz = ReflectionUtil.findClass(loader, className)
    if (clazz == null) {
        log("找不到会话适配器类: $className")
        // 签名类失联（如 3200 渠道包的 kp5/a1）：启动 DexKit 反查兜底，
        // 同时宿主锚定（见 AdaptiveResolve.maybeAdoptAdapter）仍是最主力的路径。
        DexKitResolver.resolveAsync(appContext)
        return
    }
    legacyAdapterClass = clazz
    log("ListView 分支: 适配器 = ${clazz.name} (loader=${clazz.classLoader?.javaClass?.simpleName})")

    // 适配器自己声明的位置方法（按「方法名 + 参数个数 + 非 bridge」定位）
    val okCount = hookAdapterMethod(clazz, "getCount", 0, "ListViewGetCount")
    val okView = hookAdapterMethod(clazz, "getView", 3, "ListViewGetView")
    hookAdapterMethod(clazz, "getItem", 1, "ListViewGetItem")

    // ⚠️ 类名命中 ≠ 真的是会话适配器。8.0.78（3180）实测：签名里的 `iq5/a1` 类还在，
    // 但 getCount/getView/getItem 一个都声明不出来（真正的会话适配器已经是 jo5.y0）；
    // 这时候如果继续占着 legacyAdapterClass，动态锚定会被 `if (legacyAdapterClass != null) return`
    // 直接短路 —— 结果是「类挂上了、钩子没装上」，列表既不渲染收纳组也不收拢，且不报错。
    // 所以关键方法没挂上就把位子让出来，交给宿主锚定去收编真实适配器。
    if (!okCount || !okView) {
        legacyAdapterClass = null
        log("ListView 分支放弃: ${clazz.name} 上缺少 getCount/getView（签名类名仍在但已不是会话适配器）→ 交给动态锚定")
        // 菜单宿主同样按版本换名字，签名里那份多半也失联了 —— 让 DexKit 出候选再补挂。
        DexKitResolver.onMenuCandidates = { names -> hookMenuCandidates(loader, names) }
        DexKitResolver.menuCandidates?.takeIf { it.isNotEmpty() }?.let { hookMenuCandidates(loader, it) }
        DexKitResolver.resolveAsync(appContext)
    }

    // 继承自 BaseAdapter 的方法，用实例守卫限流到会话适配器（幂等：动态解析路径可能已装）
    installBaseAdapterHooks()
}

/**
 * BaseAdapter 继承方法的 Hook（全局挂 + 实例守卫限流到会话适配器）。
 * 幂等：签名路径和动态解析（AdaptiveResolve）谁先到谁装，重复调用直接跳过。
 */
internal fun ConversationGroupHook.installBaseAdapterHooks() {
    if (baseAdapterHooksInstalled) return
    baseAdapterHooksInstalled = true
    val base = android.widget.BaseAdapter::class.java
    val intArg = Int::class.javaPrimitiveType!!
    hookBaseAdapter(base, "getItemViewType", arrayOf(intArg)) { p -> legacyHeaderType(p) }
    hookBaseAdapter(base, "getViewTypeCount", emptyArray()) { p ->
        // 收纳组行占用「微信原有 type 数量」这个新值，小节标题再占一个：
        // 写死常量的话微信将来自己新增 type 就会撞车，所以一律基于运行时原值加。
        val orig = (invokeOriginal(p) as? Int)?.takeIf { it > 0 } ?: 1
        legacyHeaderViewType = orig
        orig + 2
    }
    // 注意：**不要**让 isEnabled(收纳组行) 返回 false。
    // 那会让 AbsListView.onInterceptTouchEvent 不进 TOUCH_MODE_DOWN，触摸被行 View 吃掉：
    //   · 从收纳组行上起手拖动无法滚动列表
    //   · 行必须自己 clickable，于是和微信自己的列表项点击监听器抢这次点击 → 偶发闪退
    // 收纳组行照旧「可点」，点击由上面 AdapterView.performItemClick 的拦截统一处理。
}

/** 在类上按「方法名 + 参数个数 + 非 bridge」定位并挂载（避开 Kotlin 生成的 bridge 方法重复回调） */
internal fun ConversationGroupHook.hookAdapterMethod(clazz: Class<*>, name: String, arity: Int, key: String): Boolean {
    val m = clazz.declaredMethods.firstOrNull {
        it.name == name && it.parameterTypes.size == arity && !it.isBridge
    }
    if (m == null) {
        log("未找到方法: ${clazz.simpleName}.$name/$arity")
        return false
    }
    m.isAccessible = true
    return try {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isLegacyAdapter(param.thisObject)) return
                try {
                    when (key) {
                        "ListViewGetCount" -> onLegacyGetCount(param)
                        "ListViewGetView" -> onLegacyGetView(param)
                        "ListViewGetItem" -> onLegacyGetItem(param)
                    }
                } catch (t: Throwable) {
                    log("$key 处理异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("已挂载 Hook(ListView): ${clazz.simpleName}.${m.name}")
        true
    } catch (t: Throwable) {
        log("挂载失败 ${clazz.simpleName}.$name: ${t.message}")
        false
    }
}

/** 在 BaseAdapter 上挂方法；回调返回非 null 才改写结果 */
internal fun ConversationGroupHook.hookBaseAdapter(
    base: Class<*>,
    name: String,
    params: Array<Class<*>>,
    fn: (MethodHookParam) -> Any?
) {
    val m = try {
        base.getDeclaredMethod(name, *params)
    } catch (t: Throwable) {
        log("BaseAdapter 无方法 $name: ${t.message}")
        return
    }
    m.isAccessible = true
    try {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isLegacyAdapter(param.thisObject)) return
                try {
                    fn(param)?.let { param.result = it }
                } catch (t: Throwable) {
                    log("BaseAdapter.$name 处理异常: ${t.message}")
                }
            }
        })
        log("已挂载 Hook(ListView/BaseAdapter): $name")
    } catch (t: Throwable) {
        log("BaseAdapter.$name 挂载失败: ${t.message}")
    }
}

internal fun ConversationGroupHook.isLegacyAdapter(o: Any?): Boolean =
    o != null && legacyAdapterClass?.isInstance(o) == true

internal fun ConversationGroupHook.legacyHeaderType(param: MethodHookParam): Any? {
    val m = legacyModel ?: return null
    val pos = param.args[0] as? Int ?: return null
    if (pos !in m.entries.indices) return null
    val e = m.entries[pos]
    if (e.type == TYPE_HEADER) return legacyHeaderViewType
    // 「分组」小节标题也要独占一个 view type，否则 ListView 会把组行/会话行
    // 的 convertView 复用过来（类型不同 → 渲染成四不像）
    if (e.type == TYPE_SECTION) return legacyHeaderViewType + 1
    param.args[0] = e.realPos
    return invokeOriginal(param)
}

// ==================================================================================
// 微信数据访问（全部带缓存）
// ==================================================================================

/**
 * 适配器的「行容器」字段：8.0.79(3182) 是 `r`，3200 渠道包又漂回 `q`（类型 f）。
 * ⚠️ 名字候选会踩坑：3200 的字段 `r` 存在但类型是 g（不是容器），所以名字候选
 * 不能直接采信 —— 每个候选都必须验证「真掏得出行 List」才算数。
 * 候选顺序：名字候选(r/q) → 全字段（沿继承链）→ 第一个验证通过的缓存。
 */
internal fun ConversationGroupHook.containerOf(adapter: Any): Any? {
    val cls = adapter.javaClass
    if (containerFieldOwner === cls) {
        return containerField?.let { runCatching { it.get(adapter) }.getOrNull() }
    }
    val candidates = ArrayList<Field>(8)
    XposedHelpers.fieldOf(cls, "r")?.let { candidates.add(it) }
    XposedHelpers.fieldOf(cls, "q")?.let { candidates.add(it) }
    var cc: Class<*>? = cls
    while (cc != null && cc != Any::class.java) {
        for (f in cc.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (List::class.java.isAssignableFrom(f.type)) continue
            if (f.type == cls) continue
            if (candidates.none { it.name == f.name }) candidates.add(f.also { it.isAccessible = true })
        }
        cc = cc.superclass
    }
    for (f in candidates) {
        val obj = runCatching { f.get(adapter) }.getOrNull() ?: continue
        val rows = rowsListOf(obj) ?: continue
        containerField = f
        containerFieldOwner = cls
        rowsFieldOwner = cls
        log("行容器字段: ${cls.name}.${f.name} (${f.type.name}, rows=${rows.size})")
        return obj
    }
    containerField = null
    containerFieldOwner = cls
    return null
}

/**
 * 容器对象里的行 List：名字候选 `d` 优先，失联时结构反查（沿父类链）。
 * 验证口径是「字段值真的是 List」，而不只是类型声明。命中时顺手缓存 rowsField。
 */
private fun ConversationGroupHook.rowsListOf(container: Any): List<*>? {
    val cls = container.javaClass
    val candidates = ArrayList<Field>(4)
    XposedHelpers.fieldOf(cls, "d")?.let { candidates.add(it) }
    var cc: Class<*>? = cls
    while (cc != null && cc != Any::class.java) {
        for (f in cc.declaredFields) {
            if (!List::class.java.isAssignableFrom(f.type)) continue
            if (candidates.none { it.name == f.name }) candidates.add(f.also { it.isAccessible = true })
        }
        cc = cc.superclass
    }
    for (f in candidates) {
        val v = runCatching { f.get(container) as? List<*> }.getOrNull()
        if (v != null) {
            rowsField = f
            return v
        }
    }
    return null
}

/** 会话列表的原始行列表（8.0.79: adapter.r.d；3200: adapter.q.的 List 字段）。用 containerOf 缓存的解析结果。 */
internal fun ConversationGroupHook.legacyRows(adapter: Any): List<*>? {
    val cls = adapter.javaClass
    if (containerFieldOwner !== cls) {
        containerOf(adapter)
        if (containerFieldOwner !== cls) return null
    }
    val cf = containerField ?: return null
    val rf = rowsField?.takeIf { rowsFieldOwner === cls } ?: return null
    val container = runCatching { cf.get(adapter) }.getOrNull() ?: return null
    return runCatching { rf.get(container) as? List<*> }.getOrNull()
}

/**
 * 行 → 会话对象（k4）。8.0.78/79 都是 `row.d`（类型 com.tencent.mm.storage.k4）。
 * 「d」失联时结构反查：行上类型落在 com.tencent.mm.storage 包里的字段
 * （storage 包名是稳定的，混淆只动最后一段）。
 */
internal fun ConversationGroupHook.k4Of(row: Any?): Any? {
    row ?: return null
    XposedHelpers.getObjectField(row, "d")?.let { return it }
    val cls = row.javaClass
    val f: Field = (if (rowK4FieldOwner === cls) {
        rowK4Field
    } else {
        var hit: Field? = null
        var cc: Class<*>? = cls
        while (cc != null && cc != Any::class.java && hit == null) {
            hit = cc.declaredFields.firstOrNull {
                it.type.name.startsWith("com.tencent.mm.storage.")
            }?.also { it.isAccessible = true }
            cc = cc.superclass
        }
        rowK4Field = hit
        rowK4FieldOwner = cls
        hit
    }) ?: return null
    return runCatching { f.get(row) }.getOrNull()
}

/** talker 方法的候选名（8.0.79 `f1`，8.0.78 `i1`），命中后按类记住，只解析一次 */
private val TALKER_METHOD_CANDIDATES = arrayOf("f1", "i1")

/**
 * talker 字段名候选。`field_` 前缀 = 微信数据库列名，**不参与混淆**，跨版本最稳。
 *
 * 为什么要加这一层：混淆方法名（`f1`/`i1`）每个版本都会重排，一旦失联，
 * `talkerOf` 全线返回 null → 主列表里「已加入分组的会话」一条都收不拢
 * （表现为 250 行全显示、日志里 收拢=0）。数据库列名不会变，理应作为第一来源。
 */
private val TALKER_FIELD_NAMES = arrayOf("field_username", "field_talker")

/**
 * k4 类 → 该类的 username 字段（沿继承链解析一次；找不到也缓存 null，避免每行都扫）。
 *
 * ⚠️ 函数名叫 `usernameFieldOf` 而不是 `talkerFieldOf`：后者已经被
 * [ConversationGroupHook.talkerFieldOf]（RecyclerView 分支，参数是「行对象」）占用了。
 * 同名的话 Kotlin 会优先解析到隐式接收者的成员扩展，这里传进去的 Class 被当成行对象扫，
 * 永远返回 null —— 这个坑已经踩过一次。
 */
private val usernameFieldCache = java.util.Collections.synchronizedMap(HashMap<String, Field?>())

/**
 * @param names 候选字段名（签名优先 + 代码内默认）。
 *   缓存键带上候选列表：签名换过（例如应用了分析器产物）时不会复用旧结果。
 */
private fun usernameFieldOf(cls: Class<*>, names: List<String>): Field? {
    val ck = cls.name + "|" + names.joinToString(",")
    if (usernameFieldCache.containsKey(ck)) return usernameFieldCache[ck]
    var hit: Field? = null
    for (n in names) {
        val f = XposedHelpers.fieldOf(cls, n) ?: continue
        f.isAccessible = true
        hit = f
        break
    }
    usernameFieldCache[ck] = hit
    return hit
}

/**
 * 从会话对象 k4 上取 talker。
 *
 * 取值顺序（越靠前越可信）：
 *   ① `field_username` / `field_talker` —— 数据库列名，不参与混淆。
 *      ⚠️ 这里**不能**套 [looksLikeUsername]：真实的 username 里有一批很短的
 *      （`qqmail` / `weixin` / `notifymessage` 这类系统号与公众号），
 *      启发式会把它们误杀，结果就是这些会话永远收不进分组。
 *   ② 混淆方法 `f1`（8.0.79）/ `i1`（8.0.78），命中后按类缓存。
 *   ③ 结构反查：扫 k4 上「无参返回 String 且值长得像 wxid/群号」的方法
 *      （有真实实例可探，命中即缓存；失败也缓存，避免每次渲染都扫）。
 */
/**
 * 把「运行时真实取到值的类名 / 字段名」记下来并回传一次。
 *
 * 为什么需要它：内置分析器在模块 App 里做**静态**反查，有几项它拿不准 ——
 * 会话适配器（微信里有一百多个 BaseAdapter 子类长得一样）、field_flag /
 * field_unReadCount（DexKit 的字段索引在这份 dex 上认不出这两个名字）。
 * 但 Hook 在微信进程里是**真的**从这些字段取到了置顶位与未读数，
 * 所以它写下来的名字是最可靠的签名来源：静态反查只是没有真机时的兜底。
 *
 * 只在首次收拢诊断时写一次（[runtimeProbeLogged]），不刷屏。
 */
internal fun ConversationGroupHook.recordRuntimeProbe(k4: Any?) {
    if (runtimeProbeLogged) return
    val m = runtimeProbe
    legacyAdapterClass?.name?.let { m.putIfAbsent("adapter", it) }
    val cls = k4?.javaClass ?: return
    m.putIfAbsent("storage", cls.name)
    usernameFieldOf(cls, fieldNames("username", TALKER_FIELD_NAMES.toList()))?.name
        ?.let { m.putIfAbsent("username", it) }
    pinnedFieldOf(cls)?.name?.let { m.putIfAbsent("flag", it) }
    unreadFieldsOf(cls).firstOrNull()?.name?.let { m.putIfAbsent("unread", it) }
    if (m.isEmpty()) return
    runtimeProbeLogged = true
    log("运行时实测: " + m.entries.joinToString(" ") { "${it.key}=${it.value}" })
}

/**
 * 补记一项运行时实测值（例如长按菜单宿主类名）。
 * 首次那批已经写过日志之后再新增的项，单独补一条，保证分析器能读到全部。
 */
internal fun ConversationGroupHook.noteRuntimeProbe(key: String, value: String) {
    if (value.isBlank()) return
    if (runtimeProbe.putIfAbsent(key, value) == null && runtimeProbeLogged) {
        log("运行时实测(补充): $key=$value")
    }
}

internal fun ConversationGroupHook.talkerOf(k4: Any?): String? {
    if (k4 == null) return null
    val cls = k4.javaClass
    val f = usernameFieldOf(cls, fieldNames("username", TALKER_FIELD_NAMES.toList()))
    if (f != null) {
        val v = try {
            f.get(k4) as? String
        } catch (_: Throwable) {
            null
        }
        if (!v.isNullOrBlank()) return v
    }
    val known = talkerMethodCache[cls]
    if (known != null) {
        if (known.isEmpty()) return null  // 已知失联，别再试
        return XposedHelpers.callMethod(k4, known) as? String
    }
    for (name in TALKER_METHOD_CANDIDATES) {
        val v = XposedHelpers.callMethod(k4, name) as? String
        if (!v.isNullOrBlank() && looksLikeUsername(v)) {
            talkerMethodCache[cls] = name
            return v
        }
    }
    val m = cls.declaredMethods.firstOrNull { md ->
        md.parameterTypes.isEmpty() && md.returnType == String::class.java &&
            !java.lang.reflect.Modifier.isStatic(md.modifiers)
    }?.also { it.isAccessible = true }
    if (m == null) {
        talkerMethodCache[cls] = ""
        return null
    }
    val v = runCatching { m.invoke(k4) as? String }.getOrNull()
    if (!v.isNullOrBlank() && looksLikeUsername(v)) {
        talkerMethodCache[cls] = m.name
        log("talker 方法结构反查: ${cls.name}.${m.name}")
        return v
    }
    talkerMethodCache[cls] = ""
    return null
}

/** wxid_xxx / 手机号 / 123456789@chatroom 这类会话标识的共同特征（区别于昵称/摘要） */
private fun looksLikeUsername(v: String): Boolean =
    v.length >= 10 && v.none(Char::isWhitespace) && (v.contains('@') || v[0].isLetter())

/**
 * 未读字段候选名，按可信度排序。
 *
 * 8.0.79 实测（本次改造时打出来的对照日志）：
 *     q2=0, field_unReadCount=9 / 68 / 10 / 1
 * 也就是说 8.0.78 时代被当作「实时未读」的 `q2`，在 8.0.79 里已经**不是**未读数了
 * （混淆名整体重排，同一个名字换了语义）。真正稳定的依据是 `field_` 前缀的字段 ——
 * 那是微信数据库列名，不参与混淆，所以：
 *   1. 优先用 `field_unReadCount`（跨版本最稳）
 *   2. 没有 field_ 前缀的字段时才退回其它候选里第一个非零值
 */
private val UNREAD_FIELD_NAMES = listOf("field_unReadCount", "field_unreadCount", "q2", "unReadCount", "unreadCount")

/** k4 类 → 该类的未读候选字段（沿继承链解析一次，之后直接用） */
internal val unreadFieldsCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

internal fun ConversationGroupHook.unreadFieldsOf(cls: Class<*>): List<Field> =
    unreadFieldsCache.getOrPut(cls) {
        val names = fieldNames("unread", UNREAD_FIELD_NAMES)
        val out = ArrayList<Field>(names.size)
        for (n in names) {
            val f = XposedHelpers.fieldOf(cls, n) ?: continue
            if (out.none { it === f }) out.add(f)
        }
        out
    }

private fun readInt(f: Field, obj: Any): Int = try {
    (f.get(obj) as? Number)?.toInt() ?: 0
} catch (t: Throwable) {
    0
}

/**
 * 从会话对象 k4 上取未读数。
 *
 * ⚠️ 关键教训一：未读字段声明在**父类**上，`getDeclaredField` 只查当前类，
 * 早期版本因此永远读到 0（角标不显示）—— 这里走 `fieldOf`（沿继承链 + 缓存）。
 *
 * ⚠️ 关键教训二：**不要**取「所有候选字段的最大值」，也要小心继承下来的混淆名。
 * 8.0.79 的 `q2` 恒为 0（已不是未读数），而 `field_unReadCount` 才是真值；
 * 老实现取 max 只是碰巧能用。这里改成「优先 field_ 前缀字段」，语义明确、跨版本更稳。
 */
internal fun ConversationGroupHook.unreadOf(k4: Any?): Int {
    if (k4 == null) return 0
    val fields = unreadFieldsOf(k4.javaClass)
    if (fields.isEmpty()) return 0
    // field_ 前缀 = 数据库列名，不参与混淆，是跨版本最可信的来源
    for (f in fields) {
        if (f.name.startsWith("field_")) {
            val n = readInt(f, k4).coerceAtLeast(0)
            logUnreadDiff(fields, f, n, k4)
            return n
        }
    }
    // 没有 field_ 字段（未来改名）：退回其它候选里第一个非零值
    for (f in fields) {
        val n = readInt(f, k4)
        if (n > 0) return n
    }
    return 0
}

// ==================================================================================
// 置顶判定（决定「置顶会话要不要被收进收纳组」）
// ==================================================================================

/**
 * 置顶位 = `field_flag` 的第 62 位（0x4000_0000_0000_0000）。
 *
 * 怎么定下来的（3200 渠道包 / 微信 8.0.79 对照实验，全表 256 条会话）：
 *   Dr.li 置顶中   → field_flag = 0x400001A169B9FC1F（最高位 4 = bit62 置位），排列表第 0 位
 *   Dr.li 取消置顶 → field_flag = 0x000001A169BE7560（bit62 清零），掉到第 8 位
 * 两次采样之间**只有这一条**翻转了 bit62，且它同时从第 0 位掉到第 8 位 —— 对应上了
 * 用户「取消置顶 Dr.li」那一步操作。
 *
 * ⚠️ 早先拿 `field_flag & 0x10000000` 判置顶是错的：实测 256 条里命中 122 条
 * （这一位在本版本另有含义），大量普通会话被误判成置顶 → 该收拢的没收拢，
 * 表现就是「置顶某个对话后它反而消失了 / 取消置顶后群聊才冒出来」。
 *
 * ⚠️ `field_flag` 声明在**父类**（im/y0，类型 long J），必须沿继承链找，
 * 且是 64 位 —— 置顶位是第 62 位，用 int 读不到。
 */
private const val PINNED_FLAG_BIT = 0x4000000000000000L

/** k4 类 → 该类的置顶标记字段（沿继承链解析一次；找不到也缓存 null，避免每行都扫） */
private val pinnedFieldCache = java.util.Collections.synchronizedMap(HashMap<String, Field?>())

/** 取某个会话对象上的置顶标记字段（默认 field_flag）；没有返回 null */
private fun ConversationGroupHook.pinnedFieldOf(cls: Class<*>): Field? {
    val n = fieldName("flag", "field_flag")
    val ck = cls.name + "|" + n
    if (pinnedFieldCache.containsKey(ck)) return pinnedFieldCache[ck]
    val hit = XposedHelpers.fieldOf(cls, n)?.also { it.isAccessible = true }
    pinnedFieldCache[ck] = hit
    return hit
}

/** 该会话是否被微信置顶（置顶会话不收进收纳组，与微信原生「置顶常驻」行为一致） */
internal fun ConversationGroupHook.pinnedOf(k4: Any?): Boolean {
    if (k4 == null) return false
    val f = pinnedFieldOf(k4.javaClass) ?: return false
    val v = try {
        f.get(k4) as? Long
    } catch (_: Throwable) {
        null
    } ?: return false
    return v and PINNED_FLAG_BIT != 0L
}

/** 首个字段与其它候选值不一致时打一条日志（最多 8 条），用于跨版本核对哪个字段才是真值 */
private fun ConversationGroupHook.logUnreadDiff(fields: List<Field>, chosen: Field, value: Int, k4: Any) {
    if (unreadDiffLogged >= 8) return
    val other = fields.firstOrNull { it !== chosen } ?: return
    val ov = readInt(other, k4)
    if (ov == value) return
    unreadDiffLogged++
    log("未读字段差异: ${chosen.name}=$value, ${other.name}=$ov（以 ${chosen.name} 为准）")
}

/**
 * 未读快照（username → 未读数）。
 *
 * 组行的「组内未读总数」需要遍历成员会话，早期实现是每渲染一个组行就把整个会话列表
 * 扫一遍并逐个反射取未读（7 组 × 82 行 ≈ 上千次反射，滚动直接掉帧）。
 * 现在整表只扫一次，结果在 [UNREAD_SNAPSHOT_TTL] 内复用；新消息到达时微信会重绘列表，
 * 下一次绑定就会拿到新快照（最坏 400ms 后更新）。
 */
private const val UNREAD_SNAPSHOT_TTL = 400L

internal fun ConversationGroupHook.unreadMapFor(adapter: Any): Map<String, Int> {
    val now = SystemClock.uptimeMillis()
    val rows = legacyRows(adapter) ?: return emptyMap()
    val n = rows.size
    if (unreadSnapshotAt != 0L && unreadSnapshotRows == n && now - unreadSnapshotAt < UNREAD_SNAPSHOT_TTL) {
        return unreadSnapshot
    }
    val map = HashMap<String, Int>(n * 2)
    val names = HashMap<String, String>(n * 2)
    for (row in rows) {
        val k4 = k4Of(row) ?: continue
        val u = talkerOf(k4) ?: continue
        map[u] = unreadOf(k4)
        // 显示名顺手一起取了（同一循环、同一批字段缓存），发送者副标题就不需要第二次扫描
        displayNameOf(k4)?.let { names[u] = it }
    }
    unreadSnapshot = map
    displayNamesSnapshot = names
    unreadSnapshotAt = now
    unreadSnapshotRows = n
    return map
}

/** 收纳组未读总数：把组内每个成员会话的未读加起来（查快照，O(成员数)） */
internal fun ConversationGroupHook.groupUnread(adapter: Any, group: WxGroup): Int {
    if (group.members.isEmpty()) return 0
    val map = unreadMapFor(adapter)
    if (map.isEmpty()) return 0
    var sum = 0
    for (m in group.members) sum += map[m] ?: 0
    return sum
}

// ==================================================================================
// 显示名（未读发送者用）
// ==================================================================================

/**
 * 会话显示名的候选字段。优先备注（用户给联系人起的备注名），再昵称。
 * `field_` 前缀 = 数据库列名，不参与混淆，跨版本稳。
 */
private val DISPLAY_NAME_FIELDS = listOf("field_conRemark", "field_nickname", "field_displayName", "field_nickName")

/** k4 类 → 显示名候选字段（沿继承链解析一次） */
internal val displayNameFieldsCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

/** 备注名候选（签名可覆盖，对应 fields.remark） */
private val REMARK_FIELDS = listOf("field_conRemark")

private fun ConversationGroupHook.displayNameFieldsOf(cls: Class<*>): List<Field> =
    displayNameFieldsCache.getOrPut(cls) {
        val names = fieldNames("remark", REMARK_FIELDS) + fieldNames("nickname", DISPLAY_NAME_FIELDS)
        val out = ArrayList<Field>(names.size)
        for (n in names) {
            val f = XposedHelpers.fieldOf(cls, n) ?: continue
            if (out.none { it === f }) out.add(f)
        }
        out
    }

internal fun ConversationGroupHook.displayNameOf(k4: Any?): String? {
    if (k4 == null) return null
    for (f in displayNameFieldsOf(k4.javaClass)) {
        val v = try { (f.get(k4) as? String)?.trim() } catch (t: Throwable) { null }
        if (!v.isNullOrBlank()) return v
    }
    return null
}

/**
 * 组内未读发送者（最多前 2 个，用于副标题「A、B 和 C 发来消息」）。
 * 全查快照，不额外扫列表。顺序跟会话列表一致（最新会话在前），正好是「谁刚发消息」。
 */
internal fun ConversationGroupHook.groupSenders(adapter: Any, group: WxGroup, limit: Int = 2): List<String> {
    val map = unreadMapFor(adapter)
    if (map.isEmpty() || group.members.isEmpty()) return emptyList()
    val names = displayNamesSnapshot
    val out = ArrayList<String>(limit)
    for (m in group.members) {
        if ((map[m] ?: 0) <= 0) continue
        out.add(names[m] ?: m)
        if (out.size >= limit) break
    }
    return out
}

/** 模块设置（2s TTL 缓存，见 GroupStore.settingsXposed） */
internal var wxSettingsCache: io.github.wxgroup.reborn.core.WxSettings? = null
internal var wxSettingsAt = 0L

internal fun ConversationGroupHook.wxSettings(): io.github.wxgroup.reborn.core.WxSettings {
    val now = SystemClock.uptimeMillis()
    val c = wxSettingsCache
    if (c != null && now - wxSettingsAt < 2000L) return c
    val s = try {
        io.github.wxgroup.reborn.core.GroupStore.settingsXposed()
    } catch (t: Throwable) {
        io.github.wxgroup.reborn.core.WxSettings.DEFAULT
    }
    wxSettingsCache = s
    wxSettingsAt = now
    return s
}

// ==================================================================================
// 虚拟模型
// ==================================================================================

/**
 * 会话列表的「顺序指纹」。
 *
 * 为什么需要：虚拟模型缓存键原先只认行数，而**置顶/取消置顶、来新消息把会话顶上来**
 * 都只改变顺序、不改变行数 —— 模型里存的是真实下标，顺序一变这些下标就指向了别的
 * 会话，表现就是「分组成员被打散出来」回到主列表。
 *
 * 为什么用 identityHashCode 而不是取 talker：取 talker 要逐行反射（k4Of + talkerOf），
 * 而 getCount 每帧都会跑，256 行反射撑不住。行对象的身份哈希是一次 List.get + 一次
 * 哈希，无反射、无分配，代价可以忽略。
 *
 * ⚠️ 代价是行对象被重建（即使顺序没变）时指纹也会变，模型会多重建一次 ——
 * 这是「宁可多算一次也要保证不错位」的取舍。
 */
private fun orderStampOf(rows: List<*>): Int {
    var h = 1
    for (i in rows.indices) h = h * 31 + System.identityHashCode(rows[i])
    return h
}

internal fun ConversationGroupHook.onLegacyGetCount(param: MethodHookParam) {
    val a = param.thisObject ?: return
    // 用 q.d.size 作为权威行数：getView 就是按这个列表索引的，
    // q.a（原始 getCount 的返回值）在刷新过程中可能短暂过期甚至为 -1。
    // 拿不到行列表就直接放行给微信原逻辑（宁可这帧不折叠，也不乱给行数）。
    val rows = legacyRows(a) ?: return
    val realCount = rows.size
    if (legacyCountLogged < 5) {
        legacyCountLogged++
        val orig = invokeOriginal(param) as? Int
        log("ListView.getCount: q.a=$orig, q.d.size=$realCount")
    }
    checkAutoRefresh(a)

    val groups = sortedGroups()
    val key = ModelKey(realCount, openGroupId, groupsRev, orderStampOf(rows))
    var model = legacyModel
    if (model == null || legacyModelKey != key) {
        model = buildLegacyModel(a, realCount, groups)
        legacyModel = model
        legacyModelKey = key
        unreadSnapshotAt = 0L   // 行数/分组变了，未读快照一起失效
    }
    val sigKey = Triple(model.entries.size, model.hiddenCount, groups.size)
    if (legacyLoggedKey != sigKey) {
        legacyLoggedKey = sigKey
        log("列表构建(ListView): 分组=${groups.size} ${groups.joinToString("/") { it.name }}, 虚拟行=${model.entries.size} (真实=$realCount, 收拢=${model.hiddenCount}, 打开=${openGroupId ?: "-"})")
    }
    param.result = model.entries.size
}

internal fun ConversationGroupHook.buildLegacyModel(a: Any, realCount: Int, groups: List<WxGroup>): LegacyModel {
    // 一次遍历同时建「下标 → username」和「username → 下标」，
    // 后面按成员找下标就是 O(1)，不再对每个成员做一次 indexOf（82 × N 的线性查找）。
    val rows = legacyRows(a)
    val names = arrayOfNulls<String>(realCount)
    val posOf = HashMap<String, Int>(realCount * 2)
    // 置顶会话（微信原生行为：置顶的会话常驻列表顶部，不该被收进收纳组）
    val pinned = HashSet<String>()
    for (i in 0 until realCount) {
        val row = rows?.getOrNull(i) ?: continue
        val k4 = k4Of(row) ?: continue
        val u = talkerOf(k4) ?: continue
        names[i] = u
        if (!posOf.containsKey(u)) posOf[u] = i
        if (pinnedOf(k4) && pinned.add(u) && pinnedLogged < 12) {
            pinnedLogged++
            log("置顶会话保留在主列表(不被收纳): $u")
        }
    }
    // username -> 归属分组（第一个命中的分组胜出，避免重复收纳）
    val owner = HashMap<String, String>()
    for (g in groups) for (mm in g.members) owner.putIfAbsent(mm, g.id)

    // 收拢诊断：只打一次。用来回答「为什么已加入分组的会话还留在主列表」——
    // 关键看 talker 取没取到、分组成员在真实列表里命中了几个。
    if (!foldDiagLogged && realCount > 0 && owner.isNotEmpty()) {
        foldDiagLogged = true
        val r0 = rows?.firstOrNull()
        val k0 = k4Of(r0)
        val hit = owner.keys.count { posOf.containsKey(it) }
        log(
            "收拢诊断: 行类=${r0?.javaClass?.name}, k4类=${k0?.javaClass?.name}, " +
                "username字段=${k0?.javaClass?.let { usernameFieldOf(it, fieldNames("username", TALKER_FIELD_NAMES.toList()))?.name } ?: "无"}, " +
                "talker样例=${talkerOf(k0)}, 有talker行=${names.count { it != null }}/$realCount, " +
                "分组成员=${owner.size}, 命中=$hit, 置顶=${pinned.size}"
        )
        // 顺手把「实际取到值的类名/字段名」回传给模块 App —— 内置分析器拿它当
        // 最高置信度的结论（静态反查拿不准的适配器类名、field_flag 这些，
        // 运行时这里是唯一真凭实据）。
        recordRuntimeProbe(k0)
    }

    /** 某个分组的成员在真实列表里的下标（只取真正归属它的） */
    fun membersOf(g: WxGroup): List<Int> {
        val out = ArrayList<Int>(g.members.size)
        for (mm in g.members) {
            if (owner[mm] != g.id) continue
            posOf[mm]?.let { out.add(it) }
        }
        return out
    }

    val entries = ArrayList<VEntry>()
    var hidden = 0

    // ── 二级页面：点进某个分组后，列表「只」显示这个分组的成员，
    //    组行本身变成带「返回」的标题栏；再点一次返回完整会话列表。
    //    成员行仍然交给微信自己渲染（头像/昵称/摘要/未读都和微信原生一模一样），
    //    全程在微信内，不跳模块 App。
    val openId = openGroupId
    if (openId != null) {
        val g = groups.firstOrNull { it.id == openId }
        if (g != null) {
            val memberPos = membersOf(g)
            entries.add(VEntry(TYPE_HEADER, g, -1, memberPos.size))
            for (rp in memberPos) entries.add(VEntry(TYPE_REAL, null, rp))
            return LegacyModel(entries, realCount - memberPos.size)
        }
        // 分组已被删掉 → 退回主列表
        log("二级页面回退: 打开的分组 $openId 没找到（现有=${groups.joinToString("/") { it.id }}）")
        openGroupId = null
    }

    val hideEmpty = wxSettings().hideEmptyGroups
    // 「分组」小节标题：先占位，真有分组行再加（分组全被隐藏时不留空标题）
    var sectionAdded = false
    for (g in groups) {
        val memberPos = membersOf(g)
        // 「隐藏空分组」：成员一个都不在会话列表里的组不占行（设置页可开关）。
        // 有成员但都收拢在其他组（owner 不归它）同样按空处理 —— 用户看到的就是净列表。
        if (hideEmpty && memberPos.isEmpty()) continue
        if (!sectionAdded) {
            entries.add(VEntry(TYPE_SECTION))
            sectionAdded = true
        }
        entries.add(VEntry(TYPE_HEADER, g, -1, memberPos.size))
        hidden += memberPos.size
    }
    // 其余会话保持原顺序；属于任何分组的会话都已「收拢」（从原位隐藏）。
    // 例外：微信里被置顶的会话仍留在主列表 —— 否则用户长按置顶后会发现「这个对话消失了」。
    for (i in 0 until realCount) {
        val u = names[i]
        if (u != null && owner.containsKey(u) && u !in pinned) continue
        entries.add(VEntry(TYPE_REAL, null, i))
    }
    return LegacyModel(entries, hidden)
}

// ==================================================================================
// getView / getItem
// ==================================================================================

internal fun ConversationGroupHook.onLegacyGetView(param: MethodHookParam) {
    val pos = (param.args[0] as? Int) ?: return
    try {
        val model = legacyModel
        val e = model?.entries?.getOrNull(pos)
        val reuse = param.args.getOrNull(1) as? View
        // ── 「分组」小节标题 ──────────────────────────────────────────
        if (e != null && e.type == TYPE_SECTION) {
            val ctx = (param.args.getOrNull(2) as? ViewGroup)?.context ?: appContext ?: return
            val view = if (reuse?.tag === SECTION_TAG) reuse else createSectionRow(ctx)
            view.layoutParams = android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            param.result = view
            return
        }
        if (e != null && e.type == TYPE_HEADER && e.group != null) {
            val ctx = (param.args.getOrNull(2) as? ViewGroup)?.context ?: appContext ?: return
            // 只复用「收纳组行」这种类型；腾讯会话行的 convertView 直接丢弃，
            // 否则微信的会话行布局会套进我们的 LinearLayout 里，渲染成四不像。
            val view = if (reuse != null && reuse.tag is FolderHolder) reuse else createFolderRow(ctx)
            // ListView.setupChild 会把 LayoutParams 硬转 AbsListView.LayoutParams
            view.layoutParams = android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val adapter = param.thisObject
            val unread = if (adapter != null) groupUnread(adapter, e.group) else 0
            val senders = if (adapter != null && wxSettings().showSenders) groupSenders(adapter, e.group) else emptyList()
            bindFolderRow(view, e.group, e.memberCount, unread, senders)
            if (legacyRenderLogged < 10) {
                legacyRenderLogged++
                log("渲染收纳组行: ${e.group.name} @ pos=$pos")
            }
            param.result = view
            return
        }
        // ── 真实会话行 ──────────────────────────────────────────────────────
        // 关键一：绝不能把「收纳组行」当 convertView 交给微信自己的 getView。
        //   微信会把它强行 cast/当成自己的会话行视图去绑定 → 抛异常；
        //   而异常兜底又只能把这个 view 原样返回 → 那一行就「变成了收纳组行」。
        //   用户看到的就是「点分组下面的会话，那一行变成 展开/收纳」。
        if (reuse != null && reuse.tag is FolderHolder) {
            param.args[1] = null // 交给微信重新建一个真正的会话行视图
            if (convertDropLogged < 8) {
                convertDropLogged++
                log("丢弃收纳组行 convertView: pos=$pos（类型不匹配，改为新建会话行）")
            }
        }
        // 关键二：getView 直接索引 q.d，把虚拟位置改写为真实下标即可
        val rows = legacyRows(param.thisObject ?: return)
        val rp = if (e != null && e.type == TYPE_REAL) e.realPos else pos
        if (rows != null && rp in rows.indices) {
            param.args[0] = rp
            val v = invokeOriginal(param) as? View
            if (v != null && v.tag !is FolderHolder) {
                if (DIAG_LOG && memberRenderDiagLogged < 20) {
                    memberRenderDiagLogged++
                    log("二级页面成员渲染: pos=$pos rp=$rp tag=${v.tag?.javaClass?.name ?: "null"} openGroupId=$openGroupId")
                }
                param.result = v
                return
            }
            // ⚠️ 污染信号：真实成员行经微信 getView 后返回的视图带着 FolderHolder tag，
            // 说明微信把这个位置复用了我们之前建的收纳组行（type=orig 与真实行 type 撞车，
            // 或 ListView 跨 type 复用了 scrap）。此时必须强制让微信重建，否则这一行
            // 看起来像收纳组行 → 点击被当「组标题」吃掉（二级页面第一个成员点不开的根因候选）。
            if (memberContamLogged < 20) {
                memberContamLogged++
                log("成员行被污染: pos=$pos rp=$rp 返回视图 tag=FolderHolder！（openGroupId=$openGroupId，丢弃重来）")
            }
            // 强制用全新 convertView 再调一次，逼微信建真正会话行
            param.args[1] = null
            val v2 = runCatching { invokeOriginal(param) as? View }.getOrNull()
            if (v2 != null && v2.tag !is FolderHolder) {
                param.result = v2
                return
            }
        }
        // ⚠️ 兜底：模型与 q.d 在「微信清空/重建列表」的间隙里会对不上。
        // 这时绝不能把越界下标交回原始 getView —— 它直接索引 q.d，
        // 越界会抛异常，ListView 拿到 null 子 View 后 NPE，表现就是「点一下就闪退」。
        if (legacyGuardLogged < 12) {
            legacyGuardLogged++
            log("getView 兜底: pos=$pos, 模型行数=${model?.entries?.size ?: -1}, q.d=${rows?.size ?: -1}, rp=$rp, openGroupId=$openGroupId")
        }
        param.result = fallbackRow(param)
    } catch (t: Throwable) {
        log("getView 保护: ${t.javaClass.simpleName}: ${t.message}")
        val safe = runCatching { fallbackRow(param) }.getOrNull()
        param.result = if (safe != null && safe.tag !is FolderHolder) safe else null
    }
}

/**
 * 兜底行：宁可给一个空行，也绝不给 null、绝不用越界下标。
 * 有同类型 convertView 就原样返回（不改编它的 LayoutParams，否则回收后会污染真实会话行的高度）。
 * **绝不返回收纳组行** —— 那会让真实会话行显示成收纳组行。
 */
internal fun ConversationGroupHook.fallbackRow(param: MethodHookParam): View? {
    val reuse = param.args.getOrNull(1) as? View
    if (reuse != null && reuse.tag !is FolderHolder) return reuse
    val ctx: Context? = (param.args.getOrNull(2) as? ViewGroup)?.context ?: appContext
    if (ctx == null) return null
    val v = View(ctx)
    v.layoutParams = android.widget.AbsListView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 1
    )
    return v
}

internal fun ConversationGroupHook.onLegacyGetItem(param: MethodHookParam) {
    val pos = (param.args[0] as? Int) ?: return
    try {
        val model = legacyModel
        val e = model?.entries?.getOrNull(pos)
        if (e != null && e.type == TYPE_HEADER) {
            // 收纳组行不是微信会话；微信自己的消费方（w2/s3）都做了判空
            param.result = null
            return
        }
        val rows = legacyRows(param.thisObject ?: return)
        val rp = if (e != null && e.type == TYPE_REAL) e.realPos else pos
        if (rows == null || rp !in rows.indices) {
            param.result = null
            return
        }
        param.args[0] = rp
        param.result = invokeOriginal(param)
    } catch (t: Throwable) {
        log("getItem 保护: ${t.javaClass.simpleName}: ${t.message}")
        param.result = null
    }
}

internal fun ConversationGroupHook.legacyUsernameAt(adapter: Any, i: Int): String? {
    return try {
        val row = legacyRows(adapter)?.getOrNull(i) ?: return null
        val k4 = k4Of(row)
        talkerOf(k4)
    } catch (t: Throwable) {
        null
    }
}
