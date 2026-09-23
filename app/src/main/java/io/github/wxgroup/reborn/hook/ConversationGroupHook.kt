package io.github.wxgroup.reborn.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WeChatSignatures
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.util.ReflectionUtil
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge
import io.github.wxgroup.reborn.xp.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * 会话列表「收纳组」Hook（折叠群聊式）。
 *
 * ── 微信 8.0.78 运行时铁证 ──
 * 会话列表是 **ListView**，适配器是 **jo5/y0（BaseAdapter）**：
 *     AbsListView.setAdapter: jo5.y0 @ConversationListView
 * `ConversationListView extends android.widget.ListView`。
 * （MainUI 里另有一条 RecyclerView 分支 po5/u，但本机走的不是它；
 *  为兼容起见两条路径都保留实现，各自带实例守卫，互不干扰。）
 *
 * jo5/y0 的数据在字段 `q`（jo5/f）：
 *   q.d = List<jo5/z>  每行 z.d = com.tencent.mm.storage.k4 会话，k4.i1() = talker
 *   q.a = 缓存行数（getCount 返回它）
 * `jo5/y0.getView(pos, cv, parent)` 直接按 position 索引 q.d（不经过 getItem），
 * 所以「虚位重映射」只要改写 args[0] 即可让微信渲染正确的会话。
 *
 * ── 虚拟列表模型 ──
 *   [收纳组行A] [A的成员(展开时)] [收纳组行B] ... + 其余会话
 * 未展开分组的成员从原位隐藏（收拢），展开后成员紧跟在该分组行之后。
 * 需要改写的位置方法：getCount / getView / getItem / getItemViewType /
 * getViewTypeCount / isEnabled / areAllItemsEnabled（getItemId 恒返回 0，无需处理）。
 *
 * 所有 hook 都包 try/catch，异常时退化为透传原始结果，绝不弄崩微信。
 *
 * ── 文件分工（本类只放状态、入口和通用工具，逻辑都在同包的下述文件里，
 *    全部写成 `ConversationGroupHook` 的扩展函数，共享这里 internal 的状态）──
 *   ListHitTest.kt     列表命中判定：哪个 ListView 是我们的、点在哪一行
 *   TouchProbe.kt      按下位置探测（屏幕坐标自算，纠正微信给错的 position）
 *   ItemIntercept.kt   列表项点击/长按拦截（展开折叠、长按菜单）
 *   ListViewPath.kt    ListView 分支：适配器 hook、虚拟模型、未读数
 *   RecyclerViewPath.kt RecyclerView 分支（其它版本/机型）
 *   MenuHooks.kt       会话长按菜单：注入菜单项 + 接管点击回调
 *   GroupActions.kt    分组增删改序、图标、菜单动作落地（弹对话框）
 *   HostContext.kt     拿 Activity / 跳模块页 / 从 View 里刨 username
 *   GroupCache.kt      分组读取缓存、变更检测与自动刷新、落盘
 *   FolderRow.kt       收纳组行的 View 构建与绑定
 *   Models.kt          VEntry / LegacyModel / RvModel / FolderHolder
 */
class ConversationGroupHook {
    internal var appContext: Context? = null
    internal var sig: WeChatSignatures? = null
    internal var hookedLoader: ClassLoader? = null

    // ===== 分组缓存 =====
    /** 当前「打开」的分组 id —— 打开后会话列表只显示该分组的成员（二级页面语义） */
    internal var openGroupId: String? = null

    /** 会话列表所在的 Activity（用于拦截硬件返回键退出二级页面） */
    internal var mainActivity: WeakReference<android.app.Activity>? = null

    /** 当前是否处在二级页面（点进分组后） */
    internal val inGroupPage: Boolean get() = openGroupId != null

    /**
     * 二级页面是否「真正显示中」。
     *
     * ⚠️ 微信 8.0.78 的聊天页不是独立 Activity，而是在 LauncherUI 内部切页
     * （实测：聊天页打开时 topResumedActivity 仍是 LauncherUI）。聊天页盖上来后
     * 会话 ListView 被移出窗口（isShown=false），此时按返回键是「退出聊天」，
     * 必须放行；否则我们的 Hook 会把聊天页的返回吃掉，用户再按一次返回时
     * 分组状态已被清掉，直接落在主列表 —— 表现就是「返回带微信回主页」。
     */
    internal fun groupPageShown(): Boolean {
        val lv = legacyListView?.get() as? android.view.View ?: return false
        return lv.isShown
    }
    internal val groupCache = mutableMapOf<String, List<WxGroup>>()
    internal val cacheTs = mutableMapOf<String, Long>()
    internal var lastStamp = Long.MIN_VALUE
    internal var lastStampCheckMs = 0L
    internal val CACHE_TTL = 1500L

    // 自动刷新：模块 App 改过分组（镜像文件变更）后主动让会话列表重绘
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal var pendingRefresh = false
    internal var pollScheduled = false
    internal var adapterRef: WeakReference<Any>? = null
    internal val pollRunnable = object : Runnable {
        override fun run() {
            try {
                adapterRef?.get()?.let { detectAndRefresh(it) }
            } catch (_: Throwable) {
            }
            mainHandler.postDelayed(this, 1200L)
        }
    }

    // ===== ListView 分支（8.0.78 实际生效） =====
    internal var legacyAdapterClass: Class<*>? = null
    internal var legacyListView: WeakReference<Any>? = null
    internal var legacyModel: LegacyModel? = null
    internal var legacyModelKey: String? = null
    internal var legacyLoggedKey: Triple<Int, Int, Int>? = null
    internal var legacyCountLogged = 0
    internal var legacyRenderLogged = 0
    internal var legacyGuardLogged = 0
    /** 「错误类型的 convertView 被丢弃」日志计数 */
    internal var convertDropLogged = 0

    /** 收纳组行点击去重（两个拦截入口，300ms 内同一分组只处理一次） */
    internal var lastTapGid: String? = null
    internal var lastTapAt = 0L

    /** 收纳组行长按去重 */
    internal var lastLongGid: String? = null
    internal var lastLongAt = 0L

    /**
     * 已挂过「长按菜单点击回调」的类。
     *
     * 会话长按菜单是微信自己的 MMPopupMenu，点菜单项走的是
     * `kj5/v4.onMMMenuItemSelected(MenuItem, int)`（8.0.78 的实现类是
     * com.tencent.mm.ui.conversation.q3），**不是** MenuItem 自己的
     * OnMenuItemClickListener —— 这就是「菜单项看得见、点了没反应」的根因。
     */
    internal val hookedMenuCallbackClasses = java.util.Collections.synchronizedSet(HashSet<Class<*>>())

    /** 列表项点击诊断日志计数（排查「点的行和实际处理的行不一致」） */
    internal var itemDispatchLogged = 0
    /** 「点击已放行给微信」日志计数 */
    internal var clickPassLogged = 0
    /** 「纠正点击位置」日志计数 */
    internal var correctLogged = 0
    /** 「忽略了非微信运行时类加载器」日志计数 */
    internal var rejectedLoaderLogged = 0
    /** 「分组顺序」诊断日志计数 */
    internal var orderLogged = 0
    /** 已挂诊断 hook 的「微信点击消费方」类 */
    internal val hookedClickConsumers = java.util.Collections.synchronizedSet(HashSet<Class<*>>())
    /** 消费方诊断日志计数 */
    internal var consumerLogged = 0

    // ===== 按下位置探测（不信 ListView 的 position，自己用屏幕坐标算） =====
    internal var downAt = 0L
    internal var downPos = -1
    internal var downChildIndex = -1
    internal var downGroupId: String? = null
    internal var downLogCount = 0

    // ===== 长按菜单上下文 =====
    /** 最近一次长按到的会话（菜单项点击时要拿它做「添加到分组」的目标） */
    internal var pendingTalker: String? = null
    /** 最近一次长按所在的 Activity（弹对话框必须有它，否则 BadTokenException） */
    internal var pendingActivity: WeakReference<android.app.Activity>? = null
    /**
     * 菜单项 → 动作。键就是 `menu.add()` 返回的那个 MenuItem 实例
     * （微信 `kj5/i4.add()` 返回的就是最终渲染的那个 `kj5/j4`，所以映射可靠）。
     * 用弱引用表：菜单关掉后 item 会被回收，不至于长期持有。
     */
    internal val menuActions = WeakHashMap<Any, String>()

    // ===== RecyclerView 分支（其它机型/版本可能走这条） =====
    internal var rvAdapterClass: Class<*>? = null              // WxRecyclerAdapter
    internal var rvBaseClass: Class<*>? = null                 // vv5/n0
    internal var rvConvAdapterNames: Set<String> = emptySet()

    internal var rvDataField: Field? = null                    // WxRecyclerAdapter.data
    internal var rvHeaderField: Field? = null                  // vv5/n0.i（头部列表）
    internal var rvFooterField: Field? = null                  // vv5/n0.m（尾部列表）
    internal var rvFieldsResolved = false
    internal var rvFieldsChecked = false

    internal val rvDecision = WeakHashMap<Any, Boolean>()
    internal val rvRemapOk = WeakHashMap<Any, Boolean>()
    internal val rvModels = WeakHashMap<Any, RvModel>()
    internal val rvHeaderItem = WeakHashMap<Any, Any>()
    internal var rvBuildLogged = 0
    internal var rvActivatedLogged = 0
    internal var rvSeenLogged = 0
    internal val rvSeen = mutableSetOf<String>()
    internal val talkerFieldCache = mutableMapOf<String, Field?>()

    // ==================================================================================
    // 入口
    // ==================================================================================
    fun setup(context: Context) {
        appContext = context
        val s = GroupStore.getSignaturesXposed(context)
        if (s == null) {
            log("无法读取签名配置，收纳组功能不生效")
            return
        }
        sig = s
        log("加载微信签名: version=${s.wechatVersion}, 方法数=${s.methods.size}")

        try {
            val gs = GroupStore.getGroupsXposed(context)
            log("分组读取: ${gs.size} 个 (${gs.joinToString { "${it.name}(${it.members.size})" }}), stamp=${GroupStore.groupsStamp()}")
        } catch (t: Throwable) {
            log("分组读取异常: ${t.message}")
        }

        installCrashLogger()
        installBootstrap()
    }

    /**
     * 把微信自己的未捕获异常处理器包一层，先打完整堆栈再交回去。
     *
     * 微信装了自定义 UncaughtExceptionHandler，Java 崩溃不会进 /data/system/dropbox，
     * 也不一定进 logcat 的 crash buffer —— 排查「点一下就闪退」时完全没有线索。
     * 这里只做记录，不改变微信原有的崩溃处理行为。
     */
    private fun installCrashLogger() {
        try {
            val cur = Thread.getDefaultUncaughtExceptionHandler()
            val wrapped = Thread.UncaughtExceptionHandler { t, e ->
                try {
                    log("=== 未捕获异常 thread=${t.name} ===")
                    android.util.Log.getStackTraceString(e).split('\n').forEach { line ->
                        if (line.isNotBlank()) log("  at $line")
                    }
                    var cause = e.cause
                    var depth = 0
                    while (cause != null && depth < 3) {
                        log("  Caused by: ${cause.javaClass.name}: ${cause.message}")
                        android.util.Log.getStackTraceString(cause).split('\n').take(12).forEach { line ->
                            if (line.isNotBlank()) log("    $line")
                        }
                        cause = cause.cause
                        depth++
                    }
                } catch (_: Throwable) {
                }
                cur?.uncaughtException(t, e) ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            Thread.setDefaultUncaughtExceptionHandler(wrapped)
            log("已挂载崩溃记录器（原 handler=${cur?.javaClass?.name ?: "null"}）")
        } catch (t: Throwable) {
            log("崩溃记录器挂载失败: ${t.message}")
        }
    }

    /**
     * 引导 Hook —— 这是整条链路的命门。
     *
     * 微信 8.0.78 用了 Tinker 热修复：运行时类由 patch 包里的
     * `dalvik.system.DelegateLastClassLoader` 加载（路径形如
     * /data/user/0/com.tencent.mm/tinker/patch-xxx/dex/tinker_classN.apk），
     * 与 `lpparam.classLoader`（base.apk 的 PathClassLoader）里的同名类**不是同一个
     * Class 对象**。在 lpparam.classLoader 上 findClass 再 hook，挂的全是「影子类」，
     * 运行时一次都不会命中 —— 这就是「Hook 已挂载但完全没反应」的根因。
     *
     * 所以只在这里挂一个框架类（android.widget.AbsListView，两个加载器里是同一个
     * Class）的方法，等它被调用时拿到运行时类加载器，再用那个加载器去解析、挂载
     * 所有微信自己的类。
     */
    private fun installBootstrap() {
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.AbsListView::class.java, "setAdapter",
                android.widget.ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.args.getOrNull(0) ?: return
                        val loader = a.javaClass.classLoader ?: return
                        ensureHooked(loader)
                        if (legacyAdapterClass?.isInstance(a) == true) {
                            param.thisObject?.let { legacyListView = WeakReference(it) }
                            // 记下宿主 Activity：二级页面要靠它拦硬件返回键
                            (param.thisObject as? android.view.View)?.context
                                ?.let { activityFromContext(it) }
                                ?.let { mainActivity = WeakReference(it) }
                            mainActivity?.get()?.let { installConcreteBackHook(it) }
                            log("会话列表已确认: ${param.thisObject?.javaClass?.name} <- ${a.javaClass.name}")
                        }
                    }
                }
            )
            log("引导: 已挂载 AbsListView.setAdapter")
        } catch (t: Throwable) {
            log("引导挂载失败: ${t.message}")
        }
        installItemClickInterceptor()
        installItemLongPressInterceptor()
        installClickListenerWrapper()
        installLongClickListenerWrapper()
        installTouchDownProbe()
        installBackKeyHook()
    }

    // ==================================================================================
    /**
     * 这个加载器能不能解析出「会话适配器」？能，才算微信的运行时加载器。
     * 8.0.78 是 Tinker 热修版本，真正干活的类在 DelegateLastClassLoader(tinker_classN.apk) 里，
     * 所以必须做这个探测，不能想当然。
     */
    private fun canResolveWeChat(loader: ClassLoader, s: WeChatSignatures): Boolean {
        for (k in listOf("ConversationAdapter", "ConversationAdapterRv")) {
            val n = s.resolveClassName(k)
            if (n.isBlank()) continue
            // 签名里是 jo5/y0 这种斜杠写法，必须走 findClass 做一次 斜杠→点 的归一化
            if (ReflectionUtil.findClass(loader, n) != null) return true
        }
        if (rejectedLoaderLogged < 2) {
            rejectedLoaderLogged++
            log("忽略非微信运行时类加载器: ${loader.javaClass.simpleName} (${loader})")
        }
        return false
    }

    /**
     * 首次拿到「微信运行时类加载器」后，用它把所有真正的 Hook 挂上（只做一次）。
     *
     * 之所以要校验：微信自己的弹窗 / 列表用的是 framework 的 ListView + ArrayAdapter，
     * 那类 adapter 的 classLoader 是 BootClassLoader。若不校验就把它记成运行时加载器，
     * 既装不上任何 Hook，又会污染 hookedLoader（之后真加载器再来时会重复挂 Hook）。
     */
    private fun ensureHooked(loader: ClassLoader) {
        if (hookedLoader === loader) return
        val s = sig ?: return
        if (!canResolveWeChat(loader, s)) return
        hookedLoader = loader
        log("引导: 运行时类加载器 = ${loader.javaClass.simpleName} -> ${loader}")
        installListViewPath(loader, s)
        installRecyclerViewPath(loader, s)
        installContextMenuHook(loader, s)
        installMenuActionHook(loader, s)
    }

    internal fun toast(ctx: Context?, msg: String) {
        try {
            android.widget.Toast.makeText(ctx ?: return, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            log("Toast 失败: ${t.message}")
        }
    }

    internal fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    internal fun isDark(ctx: Context): Boolean = try {
        val uiMode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (t: Throwable) {
        false
    }

    // ===== 工具 =====
    internal fun invokeOriginal(param: MethodHookParam): Any? = try {
        param.invokeOriginal()
    } catch (t: Throwable) {
        log("invokeOriginal 失败: ${param.method.name} -> ${t.message}")
        null
    }

    internal fun log(msg: String) = XposedBridge.log(msg)
}
