package io.github.wxgroup.reborn.hook

import android.view.ContextMenu
import android.view.View
import io.github.wxgroup.reborn.core.WeChatSignatures
import io.github.wxgroup.reborn.util.ReflectionUtil
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 会话长按菜单：往微信自己的菜单里注入「添加到分组 / 新建分组 / 移出分组」。
 *
 * 8.0.78 的坑：菜单项自己的 setOnMenuItemClickListener **永远不会被调用**，
 * 真正的回调是 `kj5/v4.onMMMenuItemSelected(item, position)`，所以必须就地
 * 把那个回调类挂上（见 ensureMenuCallbackHooked 的注释）。
 */
internal const val MENU_ITEM_ADD_TO_GROUP = 9001
internal const val MENU_ITEM_NEW_GROUP = 9002
internal const val MENU_ITEM_REMOVE_FROM_GROUP = 9003

/** 菜单项动作标记（MenuItem 实例 → 动作），点谁干什么是我们自己记的，不依赖微信的 itemId */
internal const val ACTION_ADD = "add_to_group"
internal const val ACTION_NEW = "new_group"
internal const val ACTION_REMOVE = "remove_from_group"

/** 微信菜单项（MenuItem 实现）的默认类名，8.0.78 实测 */
internal const val MENU_ITEM_CLASS_DEFAULT = "kj5.j4"

/** 微信自己菜单项点击的日志计数（临时探测用） */
private var wxMenuClickLogged = 0

// ==================================================================================
// 会话菜单：「添加到分组」
// ==================================================================================
internal fun ConversationGroupHook.installContextMenuHook(loader: ClassLoader, sig: WeChatSignatures) {
    val md = sig.methods["ConversationOnCreateContextMenu"] ?: return
    if (!md.isValid) return
    val className = sig.resolveClassName(md.className)
    val clazz = ReflectionUtil.findClass(loader, className)
    if (clazz == null) {
        log("找不到菜单宿主类: $className")
        return
    }
    val paramClasses = ReflectionUtil.resolveParamClasses(loader, md.params)
    if (paramClasses.any { it == null }) {
        log("菜单参数类型解析失败: ${md.params}")
        return
    }
    try {
        XposedHelpers.findAndHookMethod(
            clazz, md.methodName, *paramClasses.requireNoNulls(),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.method.name == "onCreateContextMenu") injectGroupMenu(param)
                }

            }
        )
        log("已挂载 Hook: ConversationOnCreateContextMenu -> $className.${md.methodName}")
    } catch (t: Throwable) {
        log("菜单挂载失败: ${t.message}")
    }
}

/**
 * 往微信的长按菜单里加两项：`添加到分组`（可选具体分组）与 `新建分组`。
 * 两个动作都在**微信界面内**用对话框完成，不跳模块 App —— 体验连贯，
 * 也彻底避开跨应用 startActivity 的那些坑。
 */
internal fun ConversationGroupHook.injectGroupMenu(param: MethodHookParam) {
    val menu = param.args.firstOrNull { it is ContextMenu } as? ContextMenu ?: return
    val view = param.args.firstOrNull { it is View } as? View
    val talker = (ReflectionUtil.getFieldValue(param.thisObject, listOf("g", "talker", "username")) as? String)
        ?: view?.let { extractUsername(it) }
        ?: scanUsername(param.thisObject)
    if (talker.isNullOrBlank()) return
    try {
        val act = findActivity(param, view)
        pendingTalker = talker
        pendingActivity = act?.let { WeakReference(it) }
        // 微信长按菜单（MMPopupMenu）点菜单项**不会**走 MenuItem 自己的监听器，
        // 而是回调 `kj5/v4.onMMMenuItemSelected`。这里就地把它挂上（只挂一次）。
        ensureMenuCallbackHooked(param.thisObject)

        val addItem = menu.add(0, MENU_ITEM_ADD_TO_GROUP, 0, "添加到分组")
        menuActions[addItem] = ACTION_ADD
        addItem.setOnMenuItemClickListener {
            log("菜单点击「添加到分组」(MenuItem 监听器): talker=$talker")
            onMenuAddToGroup()
            true
        }

        val newItem = menu.add(0, MENU_ITEM_NEW_GROUP, 0, "新建分组")
        menuActions[newItem] = ACTION_NEW
        newItem.setOnMenuItemClickListener {
            log("菜单点击「新建分组」(MenuItem 监听器): talker=$talker")
            onMenuNewGroup()
            true
        }

        // 已经在某个分组里的会话，再给一个「移出分组」的出口，
        // 否则收进分组之后就没有办法在微信里拿出来了（只能开模块 App）。
        val owner = sortedGroups().firstOrNull { talker in it.members }
        if (owner != null) {
            val rmItem = menu.add(0, MENU_ITEM_REMOVE_FROM_GROUP, 0, "移出分组「${owner.name}」")
            menuActions[rmItem] = ACTION_REMOVE
            rmItem.setOnMenuItemClickListener {
                log("菜单点击「移出分组」(MenuItem 监听器): talker=$talker")
                onMenuRemoveFromGroup()
                true
            }
        }
        log("已注入菜单项「添加到分组 / 新建分组${if (owner != null) " / 移出分组" else ""}」: talker=$talker, activity=${act?.javaClass?.simpleName}, menu=${menu.javaClass.simpleName}, item=${addItem.javaClass.name}")
    } catch (t: Throwable) {
        log("注入菜单项失败: ${t.javaClass.simpleName}: ${t.message}")
    }
}

/**
 * 挂上「会话长按菜单点击」的真正回调。
 *
 * 8.0.78 的链路（运行时实测）：
 *   ConversationListView 长按 → s3.onItemLongClick → eu5/s0（MMPopupMenu）
 *     → 调用监听器 s3.onCreateContextMenu(kj5/i4, view, info)   ← 我们在这里加菜单项
 *     → 用户点某一项 → eu5/b0.onClick → s0.v.onMMMenuItemSelected(item, position)
 * 其中 `s0.v` 就是 s3 的字段 n（8.0.78 = com.tencent.mm.ui.conversation.q3）。
 *
 * 也就是说：菜单项自己的 setOnMenuItemClickListener **永远不会被调用**
 * （kj5/j4.c() 不在会话菜单这条链路上），这就是「菜单项看得见、点了没反应」的根因。
 *
 * 实现类的名字随版本变，但接口方法名 `onMMMenuItemSelected` 稳定，而且长按回调的
 * thisObject 就是 s3 —— 直接翻它的字段，谁实现了这个方法就挂谁，不依赖任何硬编码类名。
 */
internal fun ConversationGroupHook.ensureMenuCallbackHooked(holder: Any?) {
    if (holder == null) return
    try {
        var cls: Class<*>? = holder.javaClass
        var guard = 0
        while (cls != null && cls != Any::class.java && guard++ < 10) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = try {
                    f.isAccessible = true
                    f.get(holder)
                } catch (t: Throwable) {
                    null
                } ?: continue
                hookMenuCallbackClass(v.javaClass)
            }
            cls = cls.superclass
        }
    } catch (t: Throwable) {
        log("查找菜单点击回调失败: ${t.javaClass.simpleName}: ${t.message}")
    }
}

/** 某个类如果实现了 onMMMenuItemSelected(MenuItem,int)，就挂上（每类只挂一次） */
internal fun ConversationGroupHook.hookMenuCallbackClass(cls: Class<*>): Boolean {
    val m = cls.declaredMethods.firstOrNull {
        it.name == "onMMMenuItemSelected" && it.parameterTypes.size == 2 && !it.isBridge
    } ?: return false
    if (!hookedMenuCallbackClasses.add(cls)) return true
    m.isAccessible = true
    return try {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val item = param.args.getOrNull(0) ?: return
                val action = menuActions[item]
                if (action == null) {
                    // 不是我们注入的菜单项：记下标题与调用栈，用来定位「折叠该聊天」到底干了什么
                    if (wxMenuClickLogged < 25) {
                        wxMenuClickLogged++
                        val mi = item as? android.view.MenuItem
                        log("微信菜单点击: title=${mi?.title} id=${mi?.itemId} cls=${item.javaClass.name}")
                        Thread.currentThread().stackTrace.take(14).forEach { log("    at $it") }
                    }
                    return
                }
                try {
                    log("菜单点击: $action (${item.javaClass.simpleName})")
                    when (action) {
                        ACTION_ADD -> onMenuAddToGroup()
                        ACTION_NEW -> onMenuNewGroup()
                        ACTION_REMOVE -> onMenuRemoveFromGroup()
                    }
                    param.result = null // 已处理：别让微信继续它的分支
                } catch (t: Throwable) {
                    log("菜单项处理异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("已挂载 Hook: ${cls.name}.onMMMenuItemSelected（长按菜单点击）")
        true
    } catch (t: Throwable) {
        hookedMenuCallbackClasses.remove(cls)
        log("菜单回调挂载失败 ${cls.name}: ${t.message}")
        false
    }
}

/**
 * 兜底通道：某些菜单（框架 ContextMenu / 别的弹窗实现）的菜单项点击走
 * `kj5/j4.c()Z`。会话长按那条链路走的是 onMMMenuItemSelected
 * （见 ensureMenuCallbackHooked），这里挂上不吃亏，别的菜单还能命中。
 */
internal fun ConversationGroupHook.installMenuActionHook(loader: ClassLoader, sig: WeChatSignatures) {
    val name = sig.resolveClassName("ConversationMenuItem").let {
        if (it == "ConversationMenuItem") MENU_ITEM_CLASS_DEFAULT else it
    }
    val clazz = ReflectionUtil.findClass(loader, name)
    if (clazz == null) {
        log("未找到菜单项类 $name（菜单点击仅靠 setOnMenuItemClickListener）")
        return
    }
    val m = clazz.declaredMethods.firstOrNull {
        it.name == "c" && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
    }
    if (m == null) {
        log("$name 里没找到 c()Z")
        return
    }
    m.isAccessible = true
    try {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val act = menuActions[param.thisObject] ?: return
                try {
                    when (act) {
                        ACTION_ADD -> onMenuAddToGroup()
                        ACTION_NEW -> onMenuNewGroup()
                        ACTION_REMOVE -> onMenuRemoveFromGroup()
                    }
                    param.result = true // 已处理
                } catch (t: Throwable) {
                    log("菜单项处理异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("已挂载 Hook: $name.c()Z（菜单项点击）")
    } catch (t: Throwable) {
        log("菜单项点击挂载失败: ${t.message}")
    }
}
