package io.github.wxgroup.reborn.hook

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
 * RecyclerView 分支（WxRecyclerAdapter / vv5.n0）—— 其它机型/版本可能走这条。
 * 从 ConversationGroupHook 拆出：全部作为扩展函数，共享宿主类里 internal 的 rv* 状态。
 */
    // RecyclerView 分支（WxRecyclerAdapter / vv5.n0）—— 其它机型/版本可能走这条
    // ==================================================================================
    internal fun ConversationGroupHook.installRecyclerViewPath(loader: ClassLoader, sig: WeChatSignatures) {
        // 诊断：哪条路径在驱动列表（Tinker 环境下必须用运行时 loader 才看得到真实类）
        val rvCls = ReflectionUtil.findClass(loader, "androidx.recyclerview.widget.RecyclerView")
        val setAd = rvCls?.declaredMethods?.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
        if (setAd != null) {
            setAd.isAccessible = true
            try {
                XposedBridge.hookMethod(setAd, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.args.getOrNull(0)
                        log("RecyclerView.setAdapter: ${a?.javaClass?.name} @${param.thisObject?.javaClass?.simpleName}")
                    }
                })
            } catch (t: Throwable) {
                log("RecyclerView.setAdapter 挂载失败: ${t.message}")
            }
        }

        val wxName = sig.resolveClassName("ConversationAdapterRv").let {
            if (it == "ConversationAdapterRv") RV_DEFAULT_CLASS else it
        }
        val wx = ReflectionUtil.findClass(loader, wxName)
        if (wx == null) {
            log("未找到 $wxName，跳过 RecyclerView 分支")
            return
        }
        val base = wx.superclass
        if (base == null) {
            log("$wxName 无父类，跳过 RecyclerView 分支")
            return
        }
        rvAdapterClass = wx
        rvBaseClass = base
        log("RecyclerView 分支: 适配器=$wxName, 基类=${base.name}")

        rvConvAdapterNames = setOfNotNull(
            sig.resolveClassName("ConversationAdapterRv").takeIf { it != "ConversationAdapterRv" },
            "po5.u"
        ).map { it.replace('/', '.') }.toSet()
        log("RecyclerView 分支: 会话适配器候选名 = $rvConvAdapterNames")

        hookRvDeclared(base, "getItemCount", 0, "RvItemCount")
        hookRvDeclared(base, "getItemViewType", 1, "RvItemViewType")
        hookRvDeclared(base, "getItemId", 1, "RvItemId")
        hookRvDeclared(base, "onCreateViewHolder", 2, "RvCreateViewHolder")
        hookRvDeclared(base, "onBindViewHolder", 2, "RvBindViewHolder")
        hookRvDeclared(base, "onBindViewHolder", 3, "RvBindViewHolderPayload")
    }

    internal fun ConversationGroupHook.hookRvDeclared(start: Class<*>, name: String, arity: Int, key: String): Boolean {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == arity }
            if (m != null) {
                m.isAccessible = true
                return try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val a = param.thisObject ?: return
                            if (rvAdapterClass?.isInstance(a) != true) return
                            try {
                                when (key) {
                                    "RvItemCount" -> onRvItemCount(param, a)
                                    "RvItemViewType" -> onRvItemViewType(param, a)
                                    "RvItemId" -> onRvItemId(param, a)
                                    "RvCreateViewHolder" -> onRvCreateViewHolder(param, a)
                                    "RvBindViewHolder", "RvBindViewHolderPayload" -> onRvBindViewHolder(param, a)
                                }
                            } catch (t: Throwable) {
                                log("$key 处理异常: ${t.javaClass.simpleName}: ${t.message}")
                            }
                        }
                    })
                    log("已挂载 Hook(RV): ${c.simpleName}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    true
                } catch (t: Throwable) {
                    log("RV 挂载失败: ${c.simpleName}.$name/$arity -> ${t.message}")
                    false
                }
            }
            c = c.superclass
        }
        log("未找到 RV 方法: $name/$arity (起点 ${start.name})")
        return false
    }

    internal fun ConversationGroupHook.isRconversationAdapter(a: Any): Boolean {
        rvDecision[a]?.let { return it }
        if (rvConvAdapterNames.contains(a.javaClass.name)) {
            rvDecision[a] = true
            return true
        }
        val first = rvData(a)?.firstOrNull() ?: return false
        val ok = talkerFieldOf(first) != null
        rvDecision[a] = ok
        return ok
    }

    internal fun ConversationGroupHook.rvData(a: Any): List<*>? {
        if (rvDataField == null) resolveRvFields(a)
        return try {
            rvDataField?.get(a) as? List<*>
        } catch (t: Throwable) {
            null
        }
    }

    internal fun ConversationGroupHook.resolveRvFields(a: Any) {
        if (rvDataField == null) {
            var c: Class<*>? = a.javaClass
            while (c != null && c != Any::class.java) {
                val f = c.declaredFields.firstOrNull { it.name == "data" && List::class.java.isAssignableFrom(it.type) }
                if (f != null) {
                    f.isAccessible = true
                    rvDataField = f
                    break
                }
                c = c.superclass
            }
        }
        rvBaseClass?.let { base ->
            if (rvHeaderField == null) {
                base.declaredFields.firstOrNull { it.name == "i" && List::class.java.isAssignableFrom(it.type) }
                    ?.also { it.isAccessible = true; rvHeaderField = it }
            }
            if (rvFooterField == null) {
                base.declaredFields.firstOrNull { it.name == "m" && List::class.java.isAssignableFrom(it.type) }
                    ?.also { it.isAccessible = true; rvFooterField = it }
            }
        }
        rvFieldsResolved = true
    }

    internal fun ConversationGroupHook.rvListSize(f: Field?, a: Any): Int = try {
        (f?.get(a) as? List<*>)?.size ?: 0
    } catch (t: Throwable) {
        0
    }

    internal fun ConversationGroupHook.rvUsernameAt(a: Any, i: Int): String? {
        return try {
            val row = rvData(a)?.getOrNull(i) ?: return null
            val k4 = talkerFieldOf(row)?.get(row) ?: return null
            XposedHelpers.callMethod(k4, "i1") as? String
        } catch (t: Throwable) {
            null
        }
    }

    internal fun ConversationGroupHook.talkerFieldOf(row: Any): Field? {
        val cn = row.javaClass.name
        if (talkerFieldCache.containsKey(cn)) return talkerFieldCache[cn]
        var found: Field? = null
        for (f in row.javaClass.declaredFields) {
            if (f.type.name == K4_TYPE) {
                f.isAccessible = true
                found = f
                break
            }
        }
        talkerFieldCache[cn] = found
        return found
    }

    internal fun ConversationGroupHook.onRvItemCount(param: MethodHookParam, a: Any) {
        val orig = invokeOriginal(param) as? Int ?: return
        if (rvSeenLogged < 12 && rvSeen.add(a.javaClass.name)) {
            rvSeenLogged++
            log("RV.getItemCount: ${a.javaClass.name} 计数=$orig (会话适配器=${isRconversationAdapter(a)})")
        }
        if (!isRconversationAdapter(a)) return
        if (!rvFieldsResolved) resolveRvFields(a)

        val realCount = rvData(a)?.size ?: 0
        val h = rvListSize(rvHeaderField, a)
        val m = rvListSize(rvFooterField, a)
        val valid = (h + realCount + m == orig)
        if (!rvFieldsChecked) {
            rvFieldsChecked = true
            log("RecyclerView 字段校验: 头部=$h, 内容=$realCount, 尾部=$m, 原始计数=$orig -> ${if (valid) "通过" else "未通过"}")
        }
        if (!valid) {
            rvRemapOk[a] = false
            fallbackNativeHeader(a)
            return
        }
        rvRemapOk[a] = true

        checkAutoRefresh(a)
        val groups = sortedGroups()
        val key = (openGroupId ?: "-") + "|" + groups.joinToString(";") { "${it.id}:${it.members.joinToString(",")}" }
        val cached = rvModels[a]
        if (cached == null || cached.realCount != realCount || cached.key != key) {
            val model = buildRvModel(a, realCount, groups, key)
            rvModels[a] = model
            if (rvBuildLogged < 15) {
                rvBuildLogged++
                log("列表构建(RV): 分组=${groups.size}, 虚拟内容行=${model.entries.size} (真实=$realCount, 打开=${openGroupId ?: "-"})")
            }
        }
        val model = rvModels[a] ?: return
        if (rvActivatedLogged < 3) {
            rvActivatedLogged++
            log("RecyclerView 会话适配器已接管: ${a.javaClass.name}, 头部=$h, 尾部=$m")
        }
        param.result = h + model.entries.size + m
    }

    internal fun ConversationGroupHook.buildRvModel(a: Any, realCount: Int, groups: List<WxGroup>, key: String): RvModel {
        val userNames = Array(realCount) { i -> rvUsernameAt(a, i) }
        val owner = HashMap<String, String>()
        for (g in groups) for (mm in g.members) owner.putIfAbsent(mm, g.id)

        val entries = ArrayList<VEntry>()
        val perGroup = LinkedHashMap<String, Int>()
        for (g in groups) {
            val memberPos = ArrayList<Int>()
            for (mm in g.members) {
                if (owner[mm] != g.id) continue
                val rp = userNames.indexOf(mm)
                if (rp >= 0) memberPos.add(rp)
            }
            entries.add(VEntry(TYPE_HEADER, g, -1, memberPos.size))
            // 注：本机型走 ListView 分支，这里保持「内联展开」语义（用 openGroupId 驱动）
            if (openGroupId == g.id) {
                for (rp in memberPos) entries.add(VEntry(TYPE_REAL, null, rp))
            }
            perGroup[g.id] = memberPos.size
        }
        for (i in 0 until realCount) {
            val u = userNames[i]
            if (u != null && owner.containsKey(u)) continue
            entries.add(VEntry(TYPE_REAL, null, i))
        }
        return RvModel(realCount, key, entries, perGroup)
    }

    internal fun ConversationGroupHook.rvMapV2R(a: Any, vpos: Int): Int {
        val model = rvModels[a] ?: return vpos
        val h = rvListSize(rvHeaderField, a)
        val f = rvData(a)?.size ?: 0
        return when {
            vpos < h -> vpos
            vpos < h + model.entries.size -> {
                val e = model.entries[vpos - h]
                if (e.type == TYPE_HEADER) -1 else h + e.realPos
            }
            else -> h + f + (vpos - h - model.entries.size)
        }
    }

    internal fun ConversationGroupHook.rvEntryAt(a: Any, vpos: Int): VEntry? {
        val model = rvModels[a] ?: return null
        val h = rvListSize(rvHeaderField, a)
        val k = vpos - h
        if (k < 0 || k >= model.entries.size) return null
        return model.entries[k]
    }

    internal fun ConversationGroupHook.onRvItemViewType(param: MethodHookParam, a: Any) {
        if (rvRemapOk[a] != true) return
        val vpos = (param.args[0] as? Int) ?: return
        if (rvEntryAt(a, vpos)?.type == TYPE_HEADER) {
            param.result = RV_FOLDER_VIEW_TYPE
            return
        }
        val mapped = rvMapV2R(a, vpos)
        if (mapped != vpos) {
            param.args[0] = mapped
            param.result = invokeOriginal(param)
        }
    }

    internal fun ConversationGroupHook.onRvItemId(param: MethodHookParam, a: Any) {
        if (rvRemapOk[a] != true) return
        val vpos = (param.args[0] as? Int) ?: return
        val e = rvEntryAt(a, vpos)
        if (e?.type == TYPE_HEADER) {
            param.result = (e.group?.id.hashCode() ?: 0).toLong() - 4_000_000_000L
            return
        }
        val mapped = rvMapV2R(a, vpos)
        if (mapped != vpos) {
            param.args[0] = mapped
            param.result = invokeOriginal(param)
        }
    }

    internal fun ConversationGroupHook.onRvCreateViewHolder(param: MethodHookParam, a: Any) {
        if (rvRemapOk[a] != true) return
        if ((param.args[1] as? Int) != RV_FOLDER_VIEW_TYPE) return
        val parent = param.args[0] as? ViewGroup ?: return
        val ctx = parent.context ?: appContext ?: return
        val view = createFolderRow(ctx)
        view.layoutParams = makeRvLayoutParams(parent)
        param.result = wrapHolder(a, view)
    }

    internal fun ConversationGroupHook.onRvBindViewHolder(param: MethodHookParam, a: Any) {
        if (rvRemapOk[a] != true) return
        val vpos = (param.args[1] as? Int) ?: return
        val e = rvEntryAt(a, vpos)
        if (e?.type == TYPE_HEADER) {
            val g = e.group
            if (g != null) {
                val itemView = try {
                    XposedHelpers.getObjectField(param.args[0], "itemView") as? View
                } catch (t: Throwable) {
                    null
                }
                if (itemView != null) bindFolderRow(itemView, g, e.memberCount)
            }
            param.result = null
            return
        }
        val mapped = rvMapV2R(a, vpos)
        if (mapped != vpos) {
            param.args[1] = mapped
            param.result = invokeOriginal(param)
        }
    }

    internal fun ConversationGroupHook.wrapHolder(a: Any, view: View): Any? = try {
        XposedHelpers.callMethod(a, "B0", view)
    } catch (t: Throwable) {
        log("包装分组行 ViewHolder 失败: ${t.message}")
        null
    }

    /** 分组行 LayoutParams 必须是 RecyclerView.LayoutParams；parent 就是 RecyclerView 自己 */
    internal fun ConversationGroupHook.makeRvLayoutParams(parent: ViewGroup): ViewGroup.LayoutParams {
        val plain = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return try {
            val lm = XposedHelpers.callMethod(parent, "getLayoutManager")
            (XposedHelpers.callMethod(lm, "generateLayoutParams", plain) as? ViewGroup.LayoutParams) ?: plain
        } catch (t: Throwable) {
            plain
        }
    }

    /** 降级模式：字段校验没过时，用微信原生头部视图 API vv5.n0.a0(View,int,boolean) 插入 */
    internal fun ConversationGroupHook.fallbackNativeHeader(a: Any) {
        if (rvHeaderItem.containsKey(a)) return
        val ctx = appContext ?: return
        val g = sortedGroups().firstOrNull() ?: return
        try {
            val view = createFolderRow(ctx)
            bindFolderRow(view, g, countInList(g))
            val item = XposedHelpers.callMethod(a, "a0", view, RV_FOLDER_VIEW_TYPE, false)
            rvHeaderItem[a] = item ?: true
            log("降级模式：已插入原生头部行 ${g.name}")
        } catch (t: Throwable) {
            log("降级模式插入头部行失败: ${t.message}")
        }
    }

    internal fun ConversationGroupHook.countInList(group: WxGroup): Int {
        val a = adapterRef?.get() ?: return group.members.size
        return try {
            var c = 0
            val n = rvData(a)?.size ?: 0
            for (i in 0 until n) {
                val u = rvUsernameAt(a, i)
                if (u != null && group.members.contains(u)) c++
            }
            c
        } catch (t: Throwable) {
            group.members.size
        }
    }
