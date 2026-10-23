package io.github.wxgroup.reborn.hook

import android.view.View
import io.github.wxgroup.reborn.xp.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 自适应解析：签名类失联时定位真实会话适配器。
 *
 * 背景（3200 渠道包实战）：8.0.79 稳定包的会话适配器是 iq5/a1，3200 渠道包重排成
 * kp5/a1，签名 JSON 一时没跟上 → ListView 分支挂不上、收纳组不渲染。
 *
 * 两级策略（都不依赖适配器类名）：
 *
 * 1. **宿主锚定（主路径，本机 3200 即命中）**：
 *    宿主 `com.tencent.mm.ui.conversation.ConversationListView` 是微信稳定命名
 *    （不混淆，8.0.78/79 均未变，且可经签名 JSON 远程更新）。AbsListView.setAdapter
 *    的引导 Hook 里看到宿主命中 → 直接拿 args[0] 实例当适配器，先做**结构校验**
 *    （行容器 / 会话对象字段 / talker 全部运行时探测），通过才动态挂 Hook。
 *    比静态反查更准：实例不会骗人。
 *
 * 2. **DexKit 反查（兜底）**：仅当稳定宿主类名也改名时启用。扫 base.apk +
 *    最新的 Tinker patch dex，反查「声明 getView(I,View,ViewGroup) 的 BaseAdapter
 *    子类」作候选名单；之后 setAdapter 回调里实例类名命中候选 → 走同一套结构校验。
 *    ⚠️ Tinker 环境真实类在 patch dex 里（实测 loader=DelegateLastClassLoader），
 *    所以两个 dex 都要扫；DexKit 只出候选名单，最终裁决仍是运行时结构校验。
 */
// ==================================================================================
// 动态锚定（setAdapter 回调里调用）
// ==================================================================================

/** 已尝试过的「宿主<适配器」组合，防重复校验/重复日志 */
private val adoptTried = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * 引导回调里每次都调：签名类没就位时，尝试用宿主锚点 / DexKit 候选动态收编适配器。
 * 校验失败只记一次日志，不会反复打扰。
 */
internal fun ConversationGroupHook.maybeAdoptAdapter(host: Any?, adapter: Any) {
    if (legacyAdapterClass != null) return
    if (dexKitCandidates == null) dexKitCandidates = DexKitResolver.candidates
    val hostCls = (host as? View)?.javaClass ?: return
    // 稳定宿主类名（签名 JSON 的 ConversationList 键，用户可在签名页改）
    val anchor = sig?.resolveClassName("ConversationList")
    val dexHit = dexKitCandidates?.contains(adapter.javaClass.name) == true
    if (hostCls.name != anchor && !dexHit) return
    val key = hostCls.name + '<' + adapter.javaClass.name
    if (!adoptTried.add(key)) return
    adoptDynamicAdapter(hostCls, adapter)
}

/**
 * 结构校验 + 动态挂载。失败则放弃（收纳组不渲染总好过把微信列表搞乱）。
 * 必须在 setAdapter 的 afterHookedMethod 里调用（此时 ensureHooked 已跑完）。
 */
internal fun ConversationGroupHook.adoptDynamicAdapter(hostCls: Class<*>, adapter: Any) {
    val cls = adapter.javaClass
    // ① 行容器校验：拿真实实例探测（r/q 名字候选 + 结构反查都在 containerOf 里）
    val rows = try {
        legacyRows(adapter)
    } catch (t: Throwable) {
        log("动态解析异常(容器): ${t.javaClass.simpleName}: ${t.message}")
        null
    }
    if (rows == null) {
        log("动态解析放弃: 宿主命中 ${hostCls.name} 但适配器 ${cls.name} 找不到行容器字段")
        return
    }
    // ② 行结构校验：行 → 会话对象（k4）→ talker
    var structDesc = "rows=${rows.size}"
    if (rows.isNotEmpty()) {
        val row = rows[0] ?: return
        val k4 = try {
            k4Of(row)
        } catch (t: Throwable) {
            null
        }
        if (k4 == null) {
            log("动态解析放弃: 行类 ${row.javaClass.name} 上找不到会话对象字段（storage 包结构反查未命中）")
            return
        }
        val talker = talkerOf(k4)
        structDesc += ", k4=${k4.javaClass.name}, talker=${talker ?: "未命中(后续可自愈)"}"
    }
    // ③ 收编：设 legacyAdapterClass（isLegacyAdapter 守卫从此生效）并挂位置方法
    legacyAdapterClass = cls
    log("会话列表已确认(动态): ${hostCls.name} <- ${cls.name}, $structDesc")
    installBaseAdapterHooks()
    hookAdapterMethod(cls, "getCount", 0, "ListViewGetCount")
    hookAdapterMethod(cls, "getView", 3, "ListViewGetView")
    hookAdapterMethod(cls, "getItem", 1, "ListViewGetItem")
}

// ==================================================================================
// DexKit 反查（兜底：宿主锚点也改名时用）
// ==================================================================================

object DexKitResolver {

    /** 反查出的候选适配器类名；null = 还没扫完 / 未启用 */
    @Volatile
    var candidates: Set<String>? = null
        private set

    @Volatile
    private var started = false

    /**
     * 后台扫 dex：base.apk + 最新的 Tinker patch dex。
     * 查询条件刻意保守：只按「声明 getView(I,View,ViewGroup)View」+ 包名过滤，
     * 精筛交给运行时结构校验（见 [maybeAdoptAdapter]）。
     */
    fun resolveAsync(context: android.content.Context?) {
        if (started || context == null) return
        started = true
        Thread({ scan(context) }, "wxgroup-dexkit").apply { isDaemon = true }.start()
    }

    private fun scan(context: android.content.Context) {
        try {
            try {
                System.loadLibrary("dexkit")
            } catch (t: Throwable) {
                XposedBridge.log("DexKit: libdexkit 加载失败: ${t.message}（兜底反查不可用，不影响主路径）")
                return
            }
            // base.apk 之外，把 Tinker patch dex 也扫上（3200 的真实类在 patch 里）
            val apkPaths = LinkedHashSet<String>()
            context.applicationInfo?.sourceDir?.let { apkPaths.add(it) }
            runCatching {
                File("/data/user/0/com.tencent.mm/tinker")
                    .listFiles()?.filter { it.isDirectory }?.forEach { patch ->
                        patch.listFiles()?.filter { it.name.startsWith("dex") }?.forEach { dexDir ->
                            dexDir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach {
                                apkPaths.add(it.absolutePath)
                            }
                        }
                    }
            }
            val found = LinkedHashSet<String>()
            for (path in apkPaths) {
                try {
                    DexKitBridge.create(path)?.use { bridge ->
                        bridge.findClass {
                            searchPackages("com.tencent.mm")
                            matcher {
                                methods {
                                    add {
                                        name = "getView"
                                        paramTypes("int", "android.view.View", "android.view.ViewGroup")
                                        returnType = "android.view.View"
                                    }
                                }
                            }
                        }?.forEach { found.add(it.name) }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("DexKit: 扫描 $path 失败: ${t.message}")
                }
            }
            candidates = found
            XposedBridge.log(
                "DexKit 反查完成: ${found.size} 个 getView 候选 -> ${found.take(8).joinToString()}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("DexKit 反查异常: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
