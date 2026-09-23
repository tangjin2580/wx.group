package io.github.wxgroup.reborn.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.wxgroup.reborn.core.WeChatSignatures
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.util.ReflectionUtil
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * ListView 分支（8.0.78 本机实际生效）。
 *
 * 适配器 jo5/y0 的 getView 直接按 position 索引自己的行列表 `q.d`，
 * 所以「虚位重映射」只要改写 args[0] 就能让微信渲染正确的会话。
 * 这里负责：适配器 hook、虚拟模型构建、未读数统计。
 */

// ==================================================================================
// ListView 分支（jo5/y0 BaseAdapter）—— 8.0.78 本机实际生效
// ==================================================================================
internal fun ConversationGroupHook.installListViewPath(loader: ClassLoader, sig: WeChatSignatures) {
    val className = sig.resolveClassName("ConversationAdapter")
    val clazz = ReflectionUtil.findClass(loader, className)
    if (clazz == null) {
        log("找不到会话适配器类: $className")
        return
    }
    legacyAdapterClass = clazz
    log("ListView 分支: 适配器 = ${clazz.name} (loader=${clazz.classLoader?.javaClass?.simpleName})")

    // jo5/y0 自己声明的位置方法（按「方法名 + 参数个数 + 非 bridge」定位）
    hookAdapterMethod(clazz, "getCount", 0, "ListViewGetCount")
    hookAdapterMethod(clazz, "getView", 3, "ListViewGetView")
    hookAdapterMethod(clazz, "getItem", 1, "ListViewGetItem")

    // 继承自 BaseAdapter 的方法，用实例守卫限流到会话适配器
    val base = android.widget.BaseAdapter::class.java
    val intArg = Int::class.javaPrimitiveType!!
    hookBaseAdapter(base, "getItemViewType", arrayOf(intArg)) { p -> legacyHeaderType(p) }
    hookBaseAdapter(base, "getViewTypeCount", emptyArray()) { p ->
        ((invokeOriginal(p) as? Int) ?: 1) + 1
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
    if (e.type == TYPE_HEADER) return LEGACY_HEADER_VIEW_TYPE
    param.args[0] = e.realPos
    return invokeOriginal(param)
}

/** 会话列表的原始行列表 q.d */
internal fun ConversationGroupHook.legacyRows(adapter: Any): List<*>? = try {
    val q = XposedHelpers.getObjectField(adapter, "q")
    XposedHelpers.getObjectField(q, "d") as? List<*>
} catch (t: Throwable) {
    null
}

internal fun ConversationGroupHook.onLegacyGetCount(param: MethodHookParam) {
    val a = param.thisObject ?: return
    val orig = invokeOriginal(param) as? Int
    // 用 q.d.size 作为权威行数：getView 就是按这个列表索引的，
    // q.a（原始 getCount 的返回值）在刷新过程中可能短暂过期甚至为 -1。
    val realCount = (legacyRows(a)?.size ?: (orig ?: return)).coerceAtLeast(0)
    if (legacyCountLogged < 5) {
        legacyCountLogged++
        log("ListView.getCount: q.a=$orig, q.d.size=$realCount")
    }
    checkAutoRefresh(a)

    val groups = sortedGroups()
    val key = modelKey(realCount, groups)
    var model = legacyModel
    if (model == null || legacyModelKey != key) {
        model = buildLegacyModel(a, realCount, groups)
        legacyModel = model
        legacyModelKey = key
    }
    val sigKey = Triple(model.entries.size, model.hiddenCount, groups.size)
    if (legacyLoggedKey != sigKey) {
        legacyLoggedKey = sigKey
        log("列表构建(ListView): 分组=${groups.size} ${groups.joinToString("/") { it.name }}, 虚拟行=${model.entries.size} (真实=$realCount, 收拢=${model.hiddenCount}, 打开=${openGroupId ?: "-"})")
    }
    param.result = model.entries.size
}

/** 虚拟模型缓存键：真实行数 + 当前打开的分组 + 分组内容 */
internal fun ConversationGroupHook.modelKey(realCount: Int, groups: List<WxGroup>): String =
    "$realCount|${openGroupId ?: "-"}|" +
        groups.joinToString(";") { "${it.id}:${it.members.joinToString(",")}" }

internal fun ConversationGroupHook.buildLegacyModel(a: Any, realCount: Int, groups: List<WxGroup>): LegacyModel {
    val userNames = Array(realCount) { i -> legacyUsernameAt(a, i) }
    // username -> 归属分组（第一个命中的分组胜出，避免重复收纳）
    val owner = HashMap<String, String>()
    for (g in groups) for (mm in g.members) owner.putIfAbsent(mm, g.id)

    /** 某个分组的成员在真实列表里的下标（只取真正归属它的） */
    fun membersOf(g: WxGroup): List<Int> {
        val out = ArrayList<Int>()
        for (mm in g.members) {
            if (owner[mm] != g.id) continue
            val rp = userNames.indexOf(mm)
            if (rp >= 0) out.add(rp)
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

    for (g in groups) {
        val memberPos = membersOf(g)
        entries.add(VEntry(TYPE_HEADER, g, -1, memberPos.size))
        hidden += memberPos.size
    }
    // 其余会话保持原顺序；属于任何分组的会话都已「收拢」（从原位隐藏）
    for (i in 0 until realCount) {
        val u = userNames[i]
        if (u != null && owner.containsKey(u)) continue
        entries.add(VEntry(TYPE_REAL, null, i))
    }
    return LegacyModel(entries, hidden)
}

internal fun ConversationGroupHook.onLegacyGetView(param: MethodHookParam) {
    val pos = (param.args[0] as? Int) ?: return
    try {
        val model = legacyModel
        val e = model?.entries?.getOrNull(pos)
        val reuse = param.args.getOrNull(1) as? View
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
            if (adapter != null) {
                bindFolderRow(view, e.group, e.memberCount, groupUnread(adapter, e.group))
            } else {
                bindFolderRow(view, e.group, e.memberCount, 0)
            }
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
                param.result = v
                return
            }
        }
        // ⚠️ 兜底：模型与 q.d 在「微信清空/重建列表」的间隙里会对不上。
        // 这时绝不能把越界下标交回原始 getView —— 它直接索引 q.d，
        // 越界会抛异常，ListView 拿到 null 子 View 后 NPE，表现就是「点一下就闪退」。
        if (legacyGuardLogged < 8) {
            legacyGuardLogged++
            log("getView 兜底: pos=$pos, 模型行数=${model?.entries?.size ?: -1}, q.d=${rows?.size ?: -1}")
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
        val r = invokeOriginal(param)
        param.result = r
    } catch (t: Throwable) {
        log("getItem 保护: ${t.javaClass.simpleName}: ${t.message}")
        param.result = null
    }
}

internal fun ConversationGroupHook.legacyUsernameAt(adapter: Any, i: Int): String? {
    return try {
        val row = legacyRows(adapter)?.getOrNull(i) ?: return null
        val k4 = XposedHelpers.getObjectField(row, "d")
        XposedHelpers.callMethod(k4, "i1") as? String
    } catch (t: Throwable) {
        null
    }
}

/**
 * 从会话对象 k4 上取未读数。
 *
 * ⚠️ 关键教训：未读字段（field_unReadCount 等）声明在父类 j2 上，
 * `javaClass.getDeclaredField` 只查当前类、查不到继承来的字段 —— 早期版本就是
 * 因为这个原因永远读到 0，角标一直不显示。这里必须沿 superclass 链一路找上去。
 */
internal fun ConversationGroupHook.unreadOf(k4: Any?): Int {
    if (k4 == null) return 0
    // 不确定哪个字段是运行时真值（field_unReadCount 在缓存对象上可能恒 0，
    // q2 才是实时未读），干脆全部取一遍，取最大值，谁有值用谁
    var best = 0
    for (n in listOf("q2", "field_unReadCount", "field_unreadCount", "unReadCount", "unreadCount")) {
        val f = findFieldInHierarchy(k4.javaClass, listOf(n)) ?: continue
        val v = try {
            f.get(k4)
        } catch (_: Throwable) {
            null
        }
        if (v is Number && v.toInt() > best) best = v.toInt()
    }
    return best
}

/** 沿类继承链查找字段（含父类），找不到返回 null */
private fun findFieldInHierarchy(clazz: Class<*>?, names: List<String>): java.lang.reflect.Field? {
    var c = clazz
    var depth = 0
    while (c != null && depth < 6) {
        for (n in names) {
            try {
                val f = c.getDeclaredField(n)
                f.isAccessible = true
                return f
            } catch (_: Throwable) {
            }
        }
        c = c.superclass
        depth++
    }
    return null
}

/** 收纳组未读总数：把组内每个成员会话的未读加起来 */
internal fun ConversationGroupHook.groupUnread(adapter: Any, group: WxGroup): Int {
    if (group.members.isEmpty()) return 0
    val rows = legacyRows(adapter) ?: return 0
    if (rows.isEmpty()) return 0
    val names = ArrayList<String?>()
    val k4s = ArrayList<Any?>()
    for (row in rows) {
        val k4 = XposedHelpers.getObjectField(row, "d")
        names.add(if (k4 == null) null else XposedHelpers.callMethod(k4, "i1") as? String)
        k4s.add(k4)
    }
    var sum = 0
    for (m in group.members) {
        val i = names.indexOf(m)
        if (i >= 0) sum += unreadOf(k4s[i])
    }
    return sum
}
