package io.github.wxgroup.reborn.hook

import android.view.SoundEffectConstants
import android.view.View
import android.widget.AdapterView
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * 列表项点击 / 长按拦截。
 *
 * 收纳组行**不挂自己的 OnClickListener**（那样会吃掉 DOWN、没法滚动），
 * 而是在 framework 层的 performItemClick / performLongPress 两处判断：
 *   是收纳组行 → 我们处理（展开/折叠、弹操作菜单），阻止微信继续
 *   否            → 原样放行（位置保持虚拟值，getItem 里做真实位置重映射）
 */

// ==================================================================================
// 列表项点击/长按拦截 —— 收纳组行的正确打开方式
//
// 微信会话行的点击是 ListView 自己处理的：AbsListView 在 ACTION_UP 时调
// performItemClick()，再由此回调注册在 AdapterView 上的 OnItemClickListener
// （8.0.78 是 com.tencent.mm.ui.conversation.w2 / d3，名字随版本变）。
//
// 所以**不要**给收纳组行自己挂 OnClickListener：那样行必须 clickable，
// 触摸会被子 View 吃掉，ListView 收不到 DOWN → 从收纳组行上起手拖动**无法滚动列表**，
// 而且会在「谁处理这次点击」上和微信自己的监听器打架（偶发拿不到 item → 闪退）。
//
// 改成框架级拦截：收纳组行照旧交给 ListView 处理（原生按压反馈 + 正常滚动），
// 在 performItemClick / performLongPress 这两处判断是不是虚拟的收纳组行：
//   是 → 我们处理（展开/折叠、打开管理页），阻止微信继续
//   否 → 原样放行（位置保持虚拟值，getItem 里再做真实位置重映射）
// 这两处都是 framework 类，不受微信内部改名影响，跨版本最稳。
// ==================================================================================
internal fun ConversationGroupHook.installItemClickInterceptor() {
    try {
        val m = android.widget.AdapterView::class.java
            .getDeclaredMethod(
                "performItemClick", View::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val parent = param.thisObject
                    if (!isOurListView(parent)) return
                    correctTapArgs(param)
                    val pos = param.args.getOrNull(1) as? Int ?: return
                    val view = param.args.getOrNull(0) as? View
                    val g = folderGroupOf(view, parent, pos) ?: return
                    // 收纳组行：我们处理，阻止微信把虚拟行当真实会话处理
                    view?.playSoundEffect(SoundEffectConstants.CLICK)
                    logItemDispatch(view, parent, pos, "收纳组行「${g.name}」→ 展开/折叠")
                    handleHeaderTap(g.id)
                    param.result = true
                } catch (t: Throwable) {
                    log("收纳组点击拦截异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("已挂载 Hook: AdapterView.performItemClick（列表项点击）")
    } catch (t: Throwable) {
        log("performItemClick 挂载失败: ${t.message}")
    }
}

/**
 * 双保险：把微信注册到会话列表上的 OnItemClickListener 包一层。
 * 绝大多数情况下 performItemClick 已经拦下了收纳组行，这里是兜底
 * （万一某版本不走 performItemClick 或者被微信自己代理了）。
 */
internal fun ConversationGroupHook.installClickListenerWrapper() {
    val host = this
    try {
        XposedHelpers.findAndHookMethod(
            android.widget.AdapterView::class.java, "setOnItemClickListener",
            AdapterView.OnItemClickListener::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val l = param.args.getOrNull(0) as? AdapterView.OnItemClickListener ?: return
                        if (l is OurItemClickListener) return
                        if (!isOurListView(param.thisObject)) return
                        param.args[0] = OurItemClickListener(host, l)
                        log("已包装会话列表的 OnItemClickListener: ${l.javaClass.name}")
                        // 顺手把微信真正的点击消费方（ConversationListView.p0，8.0.78 是 w2）
                        // 也挂上，只为看清它到底拿什么参数、为什么放弃（诊断用）
                        hookRealConsumer(param.thisObject ?: return)
                    } catch (t: Throwable) {
                        log("包装 OnItemClickListener 失败: ${t.message}")
                    }
                }
            }
        )
        log("已挂载 Hook: AdapterView.setOnItemClickListener（列表项点击兜底）")
    } catch (t: Throwable) {
        log("setOnItemClickListener 挂载失败: ${t.message}")
    }
}

/**
 * 诊断：找出 ConversationListView 里真正处理点击的那个对象（字段 p0，8.0.78 = w2），
 * 挂上它的 onItemClick 看它收到什么、返回时做了什么。纯诊断，不影响逻辑。
 */
internal fun ConversationGroupHook.hookRealConsumer(lv: Any) {
    try {
        var cls: Class<*>? = lv.javaClass
        var guard = 0
        while (cls != null && cls != Any::class.java && guard++ < 8) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = try {
                    f.isAccessible = true
                    f.get(lv)
                } catch (t: Throwable) {
                    null
                } ?: continue
                installConsumerHook(v.javaClass)
            }
            cls = cls.superclass
        }
    } catch (t: Throwable) {
        log("查找点击消费方失败: ${t.message}")
    }
}

internal fun ConversationGroupHook.installConsumerHook(cls: Class<*>): Unit {
    val m = cls.declaredMethods.firstOrNull {
        it.name == "onItemClick" && it.parameterTypes.size == 4 && !it.isBridge
    } ?: return
    if (!hookedClickConsumers.add(cls)) return
    m.isAccessible = true
    try {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (consumerLogged < 3) {
                        consumerLogged++
                        val v = param.args.getOrNull(1) as? View
                        log("消费方 ${cls.simpleName}.onItemClick 收到: pos=${param.args.getOrNull(2)}, viewTag=${v?.tag?.javaClass?.simpleName}")
                    }
                } catch (t: Throwable) {
                }
            }
        })
        log("已挂载 Hook(诊断): ${cls.name}.onItemClick")
    } catch (t: Throwable) {
        hookedClickConsumers.remove(cls)
        log("消费方挂载失败 ${cls.name}: ${t.message}")
    }
}

/** 微信原本的列表项点击监听器；收纳组行由我们吃掉，真实会话行原样转发 */
private class OurItemClickListener(
    private val host: ConversationGroupHook,
    private val orig: AdapterView.OnItemClickListener
) : AdapterView.OnItemClickListener {
    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        var p = position
        try {
            if (host.isOurListView(parent)) {
                val g = host.folderGroupOf(view, parent, position)
                if (g != null) {
                    host.handleHeaderTap(g.id)
                    return
                }
                val np = host.truePosition(parent, view, position)
                if (np != position) {
                    if (host.correctLogged < 12) {
                        host.correctLogged++
                        host.log("纠正列表点击位置(监听器): 微信报 $position → 实际 $np")
                    }
                    p = np
                }
                if (host.clickPassLogged < 5) {
                    host.clickPassLogged++
                    val hv = host.headerCount(parent)
                    val e = host.legacyModel?.entries?.getOrNull(p - hv)
                    host.log(
                        "点击放行给微信: position=$p（原报 $position）, header=$hv, " +
                            "entry(pos-$hv)=${if (e == null) "-" else if (e.type == TYPE_HEADER) "H" else "R(real=${e.realPos})"}, " +
                                "viewTag=${view?.tag?.javaClass?.simpleName}"
                    )
                }
            }
        } catch (t: Throwable) {
            host.log("点击包装异常: ${t.javaClass.simpleName}: ${t.message}")
        }
        orig.onItemClick(parent, view, p, id)
    }
}

/**
 * 长按同理。
 *
 * 注意：`ConversationListView.setOnItemLongClickListener(l)` 会把 l 存进自己的字段，
 * 然后在 super 里注册一个**代理对象**（8.0.78 是 o3）；点击侧也是同一套路（d3）。
 * 所以这里在 AdapterView 层面包装，包到的就是那个代理，转发下去仍然会走微信原逻辑。
 */
internal fun ConversationGroupHook.installLongClickListenerWrapper() {
    val host = this
    try {
        XposedHelpers.findAndHookMethod(
            android.widget.AdapterView::class.java, "setOnItemLongClickListener",
            AdapterView.OnItemLongClickListener::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val l = param.args.getOrNull(0) as? AdapterView.OnItemLongClickListener ?: return
                        if (l is OurItemLongClickListener) return
                        if (!isOurListView(param.thisObject)) return
                        param.args[0] = OurItemLongClickListener(host, l)
                        log("已包装会话列表的 OnItemLongClickListener: ${l.javaClass.name}")
                    } catch (t: Throwable) {
                        log("包装 OnItemLongClickListener 失败: ${t.message}")
                    }
                }
            }
        )
        log("已挂载 Hook: AdapterView.setOnItemLongClickListener（列表项长按）")
    } catch (t: Throwable) {
        log("setOnItemLongClickListener 挂载失败: ${t.message}")
    }
}

private class OurItemLongClickListener(
    private val host: ConversationGroupHook,
    private val orig: AdapterView.OnItemLongClickListener
) : AdapterView.OnItemLongClickListener {
    override fun onItemLongClick(
        parent: AdapterView<*>?, view: View?, position: Int, id: Long
    ): Boolean {
        var p = position
        try {
            if (host.isOurListView(parent)) {
                val g = host.folderGroupOf(view, parent, position)
                if (g != null) return host.handleHeaderLongPress(view, g)
                val np = host.truePosition(parent, view, position)
                if (np != position) {
                    host.log("纠正列表长按位置: 微信报 $position → 实际 $np")
                    p = np
                }
            }
        } catch (t: Throwable) {
            host.log("长按包装异常: ${t.javaClass.simpleName}: ${t.message}")
        }
        return orig.onItemLongClick(parent, view, p, id)
    }
}
internal fun ConversationGroupHook.installItemLongPressInterceptor() {
    try {
        XposedHelpers.findAndHookMethod(
            android.widget.AbsListView::class.java, "performLongPress",
            View::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val parent = param.thisObject
                        if (!isOurListView(parent)) return
                        correctTapArgs(param)
                        val pos = param.args.getOrNull(1) as? Int ?: return
                        val v = param.args.getOrNull(0) as? View
                        val g = folderGroupOf(v, parent, pos) ?: return
                        param.result = handleHeaderLongPress(v, g)
                    } catch (t: Throwable) {
                        log("收纳组长按拦截异常: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        )
        log("已挂载 Hook: AbsListView.performLongPress（列表项长按）")
    } catch (t: Throwable) {
        log("performLongPress 挂载失败: ${t.message}")
    }
}
/**
 * 收纳组行被点：**进入 / 退出二级页面**。
 *
 * 二级页面的做法（和微信「折叠的群聊」一致）：点分组后，会话列表「整页」切换成
 * 这个分组的成员，组行变成带「‹ 返回」的标题栏；成员行仍然由微信自己渲染，
 * 所以头像/昵称/摘要/未读全都和微信原生一模一样，全程不跳出微信。
 *
 * 两个入口（performItemClick 拦截 + 监听器包装）都走这里，300ms 内同一分组只处理一次。
 */
internal fun ConversationGroupHook.handleHeaderTap(gid: String) {
    val now = android.os.SystemClock.uptimeMillis()
    if (lastTapGid == gid && now - lastTapAt < 300L) return
    lastTapGid = gid
    lastTapAt = now
    val g = sortedGroups().firstOrNull { it.id == gid }
    val wasOpen = openGroupId == gid
    openGroupId = if (wasOpen) null else gid
    log("收纳组点击: ${g?.name ?: gid} -> ${if (wasOpen) "返回会话列表" else "打开二级页面"}")
    notifyList()
    animatePageSwitch(entering = !wasOpen)
}

/** 退出二级页面（返回键 / 分组被删除时调用） */
internal fun ConversationGroupHook.closeGroupPage(): Boolean {
    if (openGroupId == null) return false
    openGroupId = null
    notifyList()
    animatePageSwitch(entering = false)
    return true
}

/**
 * 页面切换过渡动画。
 *
 * 我们没法真的 startActivity（那会跳出微信），所以用一个短促的横滑 + 淡入来
 * 模拟「新页面推进来」的手感。位移只取列表宽度的 12%，时间短、不挡触摸。
 */
private fun ConversationGroupHook.animatePageSwitch(entering: Boolean) {
    val lv = legacyListView?.get() as? android.widget.ListView ?: return
    lv.post {
        try {
            lv.setSelection(0)                      // 进/出二级页面都回到顶部
            val dx = lv.width * 0.18f               // 位移取列表宽度的 18%
            // 新内容从侧面推进来：进入时从右侧进，退出时从左侧退回来，
            // 配合淡入淡出，视觉上就是一次「页面切换」而不是「列表刷新」。
            lv.alpha = 0.25f
            lv.scaleX = 0.985f
            lv.translationX = if (entering) dx else -dx
            lv.animate()
                .alpha(1f)
                .scaleX(1f)
                .translationX(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } catch (t: Throwable) {
            // 动画失败无所谓，列表内容已经切好了
        }
    }
}

/**
 * 硬件返回键：二级页面打开时先退出二级页面，而不是直接退出微信。
 *
 * 只在「会话列表所在的那个 Activity」上生效，避免把聊天页/其它页面的返回也吃掉。
 *
 * 实测（HyperOS + 微信 8.0.78）：微信主界面 LauncherUI 重写了 dispatchKeyEvent，
 * 在调用 super（也就是我们的 Activity 基类 Hook）之前就执行了自己的"返回=最小化"
 * （moveTaskToBack）。所以除了基类 Hook，还要：
 *   1) 挂到 LauncherUI 具体子类上（最外层，见 installConcreteBackHook）；
 *   2) 兜底拦 moveTaskToBack —— 微信最小化必然走这个调用，拦住它最保险；
 *   3) onBackPressed 兜底里要取消原方法，否则页面关了微信照样退。
 */
internal fun ConversationGroupHook.installBackKeyHook() {
    try {
        // ── 1. Activity 基类 dispatchKeyEvent ─────────────────────────────
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java, "dispatchKeyEvent",
            android.view.KeyEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (openGroupId == null) return
                    if (mainActivity?.get() !== param.thisObject) return
                    val ev = param.args.getOrNull(0) as? android.view.KeyEvent ?: return
                    if (ev.keyCode != android.view.KeyEvent.KEYCODE_BACK) return
                    // 聊天页在 LauncherUI 内部切页：聊天盖着的时候 ListView 不可见，
                    // 这时的返回键是「退出聊天」，必须放行（见 groupPageShown 注释）
                    if (!groupPageShown()) return
                    if (ev.action == android.view.KeyEvent.ACTION_UP) {
                        log("返回键: 退出二级页面")
                        closeGroupPage()
                    }
                    param.result = true
                }
            }
        )
        log("已挂载 Hook: Activity.dispatchKeyEvent（二级页面返回键）")

        // ── 2. moveTaskToBack 兜底：微信在主列表按返回是"最小化到桌面" ──
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java, "moveTaskToBack",
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (openGroupId == null) return
                    if (mainActivity?.get() !== param.thisObject) return
                    if (!groupPageShown()) return
                    log("moveTaskToBack: 已拦截（二级页面期间禁止最小化）")
                    closeGroupPage()
                    param.result = true
                }
            }
        )
        log("已挂载 Hook: Activity.moveTaskToBack（二级页面禁止最小化兜底）")

        // ── 3. onBackPressed 兜底：务必取消原方法，否则微信继续 finish ──
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java, "onBackPressed",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (openGroupId == null) return
                    if (mainActivity?.get() !== param.thisObject) return
                    if (!groupPageShown()) return
                    log("onBackPressed: 退出二级页面（已取消原返回逻辑）")
                    closeGroupPage()
                    param.result = null
                }
            }
        )
        log("已挂载 Hook: Activity.onBackPressed（二级页面返回兜底）")
    } catch (t: Throwable) {
        log("返回键挂载失败: ${t.message}")
    }
}

/** 防止对同一个类重复挂具体子类的返回键 Hook */
private var concreteBackHookInstalled = false

/**
 * 把返回键 Hook 挂到微信主 Activity 的【具体子类】上。
 *
 * 微信 LauncherUI 重写了 dispatchKeyEvent，在 super 之前就做自己的返回处理，
 * 基类 Hook 拦不住。这里挂到子类重写的方法上，在最外层就把事件吃掉，
 * 微信自己的逻辑完全不会执行。
 */
internal fun ConversationGroupHook.installConcreteBackHook(activity: android.app.Activity) {
    if (concreteBackHookInstalled) return
    concreteBackHookInstalled = true
    val clazz = activity.javaClass
    try {
        XposedHelpers.findAndHookMethod(
            clazz, "dispatchKeyEvent",
            android.view.KeyEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (openGroupId == null) return
                    if (mainActivity?.get() !== param.thisObject) return
                    val ev = param.args.getOrNull(0) as? android.view.KeyEvent ?: return
                    if (ev.keyCode != android.view.KeyEvent.KEYCODE_BACK) return
                    // 聊天页在 LauncherUI 内部切页：聊天盖着的时候 ListView 不可见，
                    // 这时的返回键是「退出聊天」，必须放行（见 groupPageShown 注释）
                    if (!groupPageShown()) return
                    if (ev.action == android.view.KeyEvent.ACTION_UP) {
                        log("返回键(子类): 退出二级页面")
                        closeGroupPage()
                    }
                    param.result = true
                }
            }
        )
        log("已挂载 Hook: ${clazz.name}.dispatchKeyEvent（最外层返回键拦截）")
    } catch (t: Throwable) {
        // 子类没重写 dispatchKeyEvent 也没关系：基类 Hook + moveTaskToBack 兜底足够
        log("子类返回键挂载跳过: ${t.message}")
    }
}
