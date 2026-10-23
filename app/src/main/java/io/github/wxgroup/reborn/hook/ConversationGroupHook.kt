package io.github.wxgroup.reborn.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
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

    // ===== 字段名（签名驱动，带代码内默认候选兜底） =====

    /**
     * 逻辑字段（"username"/"flag"/"unread"/"remark"/"nickname"）的候选真实字段名。
     *
     * 顺序：**签名里配的在前，代码内默认候选在后**。
     * 签名优先是为了让内置分析器的产物能真正生效（8.0.78 这一类名被整体重排过，
     * 字段也可能换名）；保留默认兜底是为了签名里没配这项时行为完全不变。
     */
    internal fun fieldNames(key: String, defaults: List<String>): List<String> =
        sig?.fieldNames(key, defaults) ?: defaults

    /** 单个字段名（取第一个候选） */
    internal fun fieldName(key: String, default: String): String =
        fieldNames(key, listOf(default)).firstOrNull() ?: default

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

    /** 缓存对应的「文件变更戳」：stamp 没变就直接复用缓存，不再读文件 */
    internal val cacheStamp = mutableMapOf<String, Long>()
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
                val lv = legacyListView?.get() as? android.view.View
                // ListView 分支看列表自己；RecyclerView 分支没有 ListView，退一步看宿主窗口有没有焦点
                val visible = if (lv != null) lv.isShown else (mainActivity?.get()?.hasWindowFocus() ?: true)
                if (visible) {
                    pollIdle = 0
                    adapterRef?.get()?.let { detectAndRefresh(it) }
                } else {
                    // 列表不在屏幕上（微信在后台 / 盖着聊天页）：没必要每 1.2s 醒来 stat 一遍文件。
                    // 连续 3 次不可见就停掉轮询，等 getCount 再次被调用时（用户回到会话列表）重新拉起。
                    if (++pollIdle >= 3) {
                        pollScheduled = false
                        return
                    }
                }
            } catch (_: Throwable) {
            }
            mainHandler.postDelayed(this, 1200L)
        }
    }

    // ===== ListView 分支（8.0.78 实际生效） =====
    internal var legacyAdapterClass: Class<*>? = null
    internal var legacyListView: WeakReference<Any>? = null
    internal var legacyModel: LegacyModel? = null
    internal var legacyModelKey: ModelKey? = null
    internal var legacyLoggedKey: Triple<Int, Int, Int>? = null

    /**
     * 分组数据的「内容版本号」。每次真正从磁盘重读分组（或微信侧保存）就 +1，
     * 让虚拟模型知道要不要重建 —— 比把全部分组成员的字符串拿来当 key 便宜得多。
     */
    internal var groupsRev = 0

    /** 收纳组行实际使用的 view type（运行时取微信自己的 count，见 getViewTypeCount hook） */
    internal var legacyHeaderViewType = LEGACY_HEADER_VIEW_TYPE

    /**
     * talker 取值方法的解析结果：k4 类 → 命中的方法名（8.0.78 `i1`，8.0.79 `f1`）。
     * 不缓存的话每个会话最多要试 2 个方法名，而 `callMethod` 未命中也要扫一遍候选。
     */
    internal val talkerMethodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()

    /** 适配器的「行容器」字段（8.0.79 `r`，8.0.78 `q`）：解析一次，之后直接 Field.get */
    internal var containerField: Field? = null
    internal var containerFieldOwner: Class<*>? = null

    /**
     * 微信「真实会话行」View 的 tag 类型（8.0.78 是 jo5.s）。
     *
     * 运行时从真实行上取一次，用来判断某个 View 是不是**这一轮模型**的行：
     * 模型刚切换（进出二级页面）时 ListView 可能还留着旧子 View，标签为 null，
     * 交给微信处理会按会话行结构 findViewById 拿到 null → NPE 闪退。
     */
    internal var hostRowTagClass: Class<*>? = null

    /**
     * 未读快照：username → 未读数。
     *
     * 组行每渲染一次都要「组内成员未读之和」，早期实现是每渲染一个组行就把整个会话列表
     * 扫一遍、逐个反射取未读 —— 7 个组行 × 82 行 ≈ 上千次反射。改成整表扫一次 + 短 TTL 复用，
     * 既保证新消息后角标能自己更新（400ms 内），又不会把渲染路径拖慢。
     */
    internal var unreadSnapshot: Map<String, Int> = emptyMap()
    /** username → 显示名（备注/昵称），与未读快照同一轮扫描产出，供「发送者副标题」用 */
    internal var displayNamesSnapshot: Map<String, String> = emptyMap()
    internal var unreadSnapshotAt = 0L
    internal var unreadSnapshotRows = -1
    internal var unreadDiffLogged = 0
    /** 「置顶会话保留在主列表」日志的条数上限（每次建模型都会算一遍，不限制会刷屏） */
    internal var pinnedLogged = 0
    /** 收拢诊断（行类/k4/talker 命中数）只打一次，避免刷屏 */
    internal var foldDiagLogged = false
    /** 长按诊断（performLongPress / 长按监听器各走哪条路）条数上限 */
    internal var longPressDiagLogged = 0

    /**
     * 运行时实测到的类名 / 字段名（adapter / storage / menu / username / flag / unread …）。
     * 由 [recordRuntimeProbe] 在首次成功解析时填一次，写进运行日志供内置分析器读取。
     */
    internal val runtimeProbe = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
    internal var runtimeProbeLogged = false

    /** 列表不可见时的连续空转次数（用于退避停轮询，省电） */
    internal var pollIdle = 0

    // ===== 微信内拖动排序（不跳模块 App）=====
    /** 是否处于「拖动排序」模式：开启后列表触摸由 ReorderInPlace 接管 */
    internal var reorderMode = false
    /** 当前被拖动的分组 id / 在原分组顺序里的下标 */
    internal var dragGroupId: String? = null
    internal var dragFromIndex = -1
    /** 拖动落点对应的目标下标（分组顺序里的位置） */
    internal var dragTargetIndex = -1
    /** 手指按下点、拖动视图的相对偏移 */
    internal var dragDownY = 0f
    internal var dragOffsetY = 0f
    internal var dragging = false
    /** 悬浮的行视图与绿色插入指示线（都挂在 ListView 的 overlay 上） */
    internal var dragFloating: View? = null
    internal var dragIndicator: View? = null
    /** 被拖行原视图（拖动期间临时变淡） */
    internal var dragSourceRow: View? = null
    /** 被拖行的高度、落点槽位的 Y（视口坐标）、最近一次触摸 Y（自动滚动用） */
    internal var dragRowHeight = 0
    internal var dragSlotTop = -1f
    internal var dragLastTouchY = 0f
    /** 拖到列表上下边缘时持续滚动的循环任务 */
    internal var dragAutoScroll: Runnable? = null

    internal var legacyCountLogged = 0
    internal var legacyRenderLogged = 0
    internal var legacyGuardLogged = 0
    /** 「错误类型的 convertView 被丢弃」日志计数 */
    internal var convertDropLogged = 0
    /** 二级页面成员行正常渲染计数（诊断用） */
    internal var memberRenderDiagLogged = 0
    /** 二级页面成员行被污染（返回 FolderHolder tag）计数（诊断用） */
    internal var memberContamLogged = 0

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

    // ===== ListView 适配器诊断（换微信版本后定位真实适配器类名用） =====
    /** 已记录过日志的适配器类名（去重，同名只记一次） */
    internal val adaptersSeen = java.util.Collections.synchronizedSet(HashSet<String>())
    /** 诊断日志条数上限：防某些界面疯狂换适配器刷屏 */
    internal val ADAPTER_SEEN_LOG_LIMIT = 40

    // ===== 自适应解析（AdaptiveResolve.kt 共用状态） =====
    /** BaseAdapter 继承方法的全局 hook 是否已装（签名路径 / 动态解析幂等共用） */
    @Volatile
    internal var baseAdapterHooksInstalled = false
    /** DexKit 反查出的候选适配器类名（宿主锚点失联时的兜底） */
    @Volatile
    internal var dexKitCandidates: Set<String>? = null
    /** 行列表字段（容器类 → Field，解析一次缓存） */
    internal var rowsField: Field? = null
    internal var rowsFieldOwner: Class<*>? = null
    /** 行 → 会话对象字段（行类 → Field，解析一次缓存） */
    internal var rowK4Field: Field? = null
    internal var rowK4FieldOwner: Class<*>? = null
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
    /** 按下点的屏幕坐标（「已读」按钮这类行内热区要靠它判定） */
    internal var downRawX = 0f
    internal var downRawY = 0f
    internal var downLogCount = 0

    /** 微信 ConversationStorage（l4）实例：懒解析，一次成功就缓存 */
    internal var storageRef: Any? = null
    /** 是否已经做过一次「存储解析预热」（只为把结果打进日志，方便换版本后核对） */
    internal var storageWarmUpDone = false

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
    internal val rvHolderFactoryCache = HashMap<Class<*>, java.lang.reflect.Method?>()

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
            // 启动时把「微信私有目录 ↔ /data/local/tmp 镜像」对齐一次（读路径不再写文件）
            GroupStore.reconcileXposed()
        } catch (t: Throwable) {
            log("分组读取异常: ${t.message}")
        }

        installCrashLogger()
        installBootstrap()
        installProviderObserver(context)
    }

    /**
     * 事件驱动的刷新通道：模块 App 每次写分组都会对 Provider URI 调 notifyChange，
     * 这里注册观察者，收到就清缓存 + 重建模型 —— 比 1.2s 轮询快，也省电。
     * 注册失败（极端 ROM / 权限）没关系，轮询仍然兜底。
     */
    private fun installProviderObserver(context: Context) {
        try {
            val uri = android.net.Uri.parse(io.github.wxgroup.reborn.core.GroupStore.PROVIDER_URI)
            context.contentResolver.registerContentObserver(
                uri, true,
                object : android.database.ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        try {
                            GroupStore.seedMirrorIfEmptyXposed()
                            synchronized(groupCache) {
                                groupCache.clear()
                                cacheTs.clear()
                            }
                            groupsRev++
                            val a = adapterRef?.get()
                            if (a != null) {
                                invalidateLegacyModel(a)
                                XposedHelpers.callMethod(a, "notifyDataSetChanged")
                            }
                            lastStamp = io.github.wxgroup.reborn.core.GroupStore.groupsStamp()
                            log("分组已变更(Provider 通知)，已刷新会话列表")
                        } catch (t: Throwable) {
                            log("处理分组变更通知失败: ${t.message}")
                        }
                    }
                }
            )
            log("已注册分组变更观察者（Provider 通知通道）")
        } catch (t: Throwable) {
            log("注册分组变更观察者失败（不影响轮询兜底）: ${t.message}")
        }
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
    /**
     * ListView 适配器诊断：每个适配器类名只记一次（上限 [ADAPTER_SEEN_LOG_LIMIT] 条）。
     *
     * 目的：换微信版本后，从 logcat（或模块 App 的「运行日志」页）直接读出会话列表
     * 真实适配器的类名，省去 jadx 全量反编译。3200 渠道包的 iq5/a1 失联就是
     * 靠这一行发现的。
     */
    internal fun logAdapterSeen(adapter: Any, host: Any?) {
        val cls = adapter.javaClass.name
        if (!adaptersSeen.add(cls)) return
        if (adaptersSeen.size > ADAPTER_SEEN_LOG_LIMIT) return
        log("引导: AbsListView.setAdapter: $cls @${host?.javaClass?.name} (loader=${adapter.javaClass.classLoader?.javaClass?.simpleName})")
    }

    private fun installBootstrap() {
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.AbsListView::class.java, "setAdapter",
                android.widget.ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.args.getOrNull(0) ?: return
                        val loader = a.javaClass.classLoader ?: return
                        // 诊断：把每个出现过的 ListView 适配器类名记一笔（去重限量）。
                        // 换微信版本后第一件事看这行 —— 3200 渠道包就是从这里发现
                        // iq5/a1 已经不存在、真实适配器换了类名。
                        logAdapterSeen(a, param.thisObject)
                        ensureHooked(loader)
                        // 动态解析（3200 渠道包即此路径命中）：签名适配器类失联时，
                        // 用稳定宿主类名 / DexKit 候选锚定真实适配器，结构校验通过后挂载。
                        // 放在 isInstance 判定之前 —— 收编成功后本次回调顺势走原有记录逻辑。
                        maybeAdoptAdapter(param.thisObject, a)
                        if (legacyAdapterClass?.isInstance(a) == true) {
                            param.thisObject?.let { legacyListView = WeakReference(it) }
                            // 记下宿主 Activity：二级页面要靠它拦硬件返回键
                            (param.thisObject as? android.view.View)?.context
                                ?.let { activityFromContext(it) }
                                ?.let { mainActivity = WeakReference(it) }
                            mainActivity?.get()?.let { installConcreteBackHook(it) }
                            log("会话列表已确认: ${param.thisObject?.javaClass?.name} <- ${a.javaClass.name}")
                            // 预热一次「已读」用的 ConversationStorage 解析：结果直接进日志，
                            // 换微信版本后看一行日志就知道混淆名有没有变，不用真去点按钮。
                            if (!storageWarmUpDone) {
                                storageWarmUpDone = true
                                mainHandler.postDelayed({ conversationStorage() }, 4000L)
                            }
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
        installReorderTouchHook()
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
        installContextMenuTrap()   // 8.0.78 起的主力：动态捕获菜单宿主，不依赖签名类名
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
