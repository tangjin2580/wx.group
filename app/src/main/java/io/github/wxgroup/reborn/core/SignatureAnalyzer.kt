package io.github.wxgroup.reborn.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 内置签名分析器：用 DexKit 反查微信 dex，自动生成/更新签名 JSON。
 *
 * 为什么需要它：微信每个版本都会重排混淆类名，而签名 JSON 是**一份**写死的版本
 * （assets/wechat_signatures.json）。版本一换，类名就对不上 —— 8.0.78 上出现过
 * 「签名里的 iq5.a1 类名还在，但它已经不是会话适配器」这种最坏情况：类能找到，
 * 方法挂不上，分支半死且不报错。靠人肉 jadx 逆向跟不上版本节奏。
 *
 * 设计原则（v1）：
 *  1. **只解决会被混淆的东西**：类名 + 字段名。方法名（getView / onCreateContextMenu /
 *     onMMMenuItemSelected）来自 Android 框架或接口契约，不会混淆，因此不反查，
 *     直接沿用现有签名的 methods 段。
 *  2. **以现用签名为基底**：只覆盖「反查成功且能收敛到唯一答案」的项，
 *     反查不出/多候选的就保留原值 —— 宁可少改，也不能把能用的签名改坏。
 *  3. **多选一靠强特征，不靠猜包名**：例如会话适配器用
 *     「声明 getItem(int) 且返回会话对象类」定位，这个特征在 8.0.78/79 都成立；
 *     菜单宿主用「onCreateContextMenu + onItemLongClick 同时存在」取交集收敛。
 *  4. 全程后台线程调用（扫 200MB+ dex 很慢），进度通过 [analyze] 的 onProgress 回调。
 *
 * ⚠️ 只在模块 App 进程使用；Hook 侧（微信进程）各有自己的 DexKitResolver。
 */
object SignatureAnalyzer {

    private const val TAG = "WxSigAnalyzer"

    /**
     * 分析产物的 revision 基准。必须**远高于**任何内置签名，否则
     * GroupStore.getSignaturesXposed 里「内置 revision 更高就用内置」的规则
     * 会把分析产物压掉（内置目前是 7）。
     */
    const val ANALYZED_REVISION_BASE = 1_000_000

    private const val WECHAT_PKG = WeChatKiller.WECHAT_PACKAGE
    private const val TMP_SCAN_DIR = "/data/local/tmp/wxg_dexscan"

    /** 反查结论的置信度 */
    enum class Level { OK, AMBIGUOUS, MISSING }

    /** 单项反查结果 */
    data class Finding(
        val key: String,
        val label: String,
        val value: String?,
        val level: Level,
        val candidates: List<String> = emptyList(),
        val note: String = ""
    )

    /** 一次完整分析的报告 */
    data class Report(
        val wechatVersion: String,
        val dexSources: List<String> = emptyList(),
        val findings: List<Finding> = emptyList(),
        val json: String? = null,
        val error: String? = null
    ) {
        val ok: Boolean get() = json != null
        /** 结论里有多少项来自「Hook 侧运行时实测」（比静态反查可信） */
        val runtimeUsed: Int get() = findings.count { it.note.contains("运行时实测") }
        val resolved: Int get() = findings.count { it.level == Level.OK }
        val ambiguous: Int get() = findings.count { it.level == Level.AMBIGUOUS }
        val missing: Int get() = findings.count { it.level == Level.MISSING }
    }

    // ==================================================================================
    // 对外入口
    // ==================================================================================

    /**
     * 分析已安装的微信，产出签名 JSON。
     * 必须在后台线程调用。
     *
     * @param onProgress 进度文案（"收集 dex…" / "扫描 base.apk…"），在调用线程回调
     */
    fun analyze(ctx: Context, onProgress: (String) -> Unit = {}): Report {
        val version = GroupStore.installedWechatVersion(ctx)
        if (version.isNullOrBlank()) {
            return Report(wechatVersion = "", error = "未检测到已安装的微信")
        }
        onProgress("收集 dex…")
        val sources = collectDexSources(ctx, onProgress)
        if (sources.isEmpty()) {
            return Report(version, error = "读不到微信的 dex（需要 root 授权）")
        }
        onProgress("加载 dexkit…")
        try {
            System.loadLibrary("dexkit")
        } catch (t: Throwable) {
            return Report(version, sources.keys.toList(), error = "libdexkit 加载失败: ${t.message}")
        }

        // 每个逻辑项 → 所有 dex 里扫到的候选（合并去重，保序）
        val acc = LinkedHashMap<String, LinkedHashSet<String>>()
        // 会话对象类本身（用来枚举字段名），跨 dex 只留第一个命中的
        val storageChain = ArrayList<org.luckypray.dexkit.result.ClassData>()
        for ((path, tag) in sources) {
            onProgress("扫描 $tag…")
            try {
                DexKitBridge.create(path)?.use { bridge -> probe(bridge, acc, storageChain) }
            } catch (t: Throwable) {
                Log.w(TAG, "扫描 $path 失败: ${t.message}")
            }
        }

        onProgress("汇总…")
        val runtime = readRuntimeProbe()
        val findings = finalize(acc, runtime)
        val json = buildJson(ctx, version, findings)
        return Report(
            wechatVersion = version,
            dexSources = sources.keys.toList(),
            findings = findings,
            json = json,
            error = if (json == null) "组装签名失败" else null
        )
    }

    // ==================================================================================
    // dex 源收集（base.apk / split / Tinker patch）
    // ==================================================================================

    /** path → 展示名（base.apk / split0 / tinker-3） */
    private fun collectDexSources(ctx: Context, onProgress: (String) -> Unit): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        // ① 已安装 APK：/data/app 下的 base.apk 与 split 都是全局可读的，模块 App 直接读
        runCatching {
            @Suppress("DEPRECATION")
            val ai = ctx.packageManager.getApplicationInfo(WECHAT_PKG, 0)
            ai.sourceDir?.let { out[it] = "base.apk" }
            ai.splitSourceDirs?.forEachIndexed { i, p -> out[p] = "split$i" }
        }.onFailure { Log.w(TAG, "读取微信 ApplicationInfo 失败: ${it.message}") }

        // ② Tinker 热补丁 dex：微信私有目录，模块 App 读不到 → 用 root 拷到 /data/local/tmp
        //    真机上 8.0.79/3200 渠道包的真实类就在 patch dex 里，只扫 base.apk 会漏。
        if (copyTinkerDex()) {
            File(TMP_SCAN_DIR).listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".dex")) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(6)                       // 补丁可能很多，只取最近几个，控制扫描耗时
                ?.forEachIndexed { i, f -> out[f.absolutePath] = "tinker-$i" }
        }
        if (out.isNotEmpty()) onProgress("待扫描 ${out.size} 个 dex…")
        return out
    }

    /** 用 su 把 Tinker patch dex 拷到可读目录。失败不影响主流程（base.apk 通常够用） */
    private fun copyTinkerDex(): Boolean {
        val cmd = "rm -rf $TMP_SCAN_DIR && mkdir -p $TMP_SCAN_DIR && " +
            "for f in /data/user/0/$WECHAT_PKG/tinker/*/dex*/*.apk; do " +
            "[ -f \"\$f\" ] && cp \"\$f\" $TMP_SCAN_DIR/ ; done ; " +
            "chmod -R 777 $TMP_SCAN_DIR 2>/dev/null; echo done"
        val r = suExec(cmd, timeoutMs = 25_000)
        if (!r.first) Log.w(TAG, "拷贝 Tinker dex 失败: ${r.second}")
        File(TMP_SCAN_DIR).listFiles()?.let { if (it.isEmpty()) return false }
        return File(TMP_SCAN_DIR).exists()
    }

    /** 执行 su 命令，带超时。返回 (是否成功, 输出或错误) */
    private fun suExec(cmd: String, timeoutMs: Long = 15_000): Pair<Boolean, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.outputStream.close()
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) {
            p.destroy()
            false to "超时"
        } else {
            val out = p.inputStream.bufferedReader().use { it.readText() }
            (p.exitValue() == 0) to out
        }
    } catch (t: Throwable) {
        false to (t.message ?: t.javaClass.simpleName)
    }

    // ==================================================================================
    // DexKit 反查
    // ==================================================================================

    /**
     * 在一个 dex 上跑全部查询，结果并入 acc。
     *
     * 所有查询都单独 try/catch：DexKit 的 matcher 对「参数类型写错」这类情况会抛，
     * 不能让一项失败拖垮整次分析。
     */
    private fun probe(
        bridge: DexKitBridge,
        acc: LinkedHashMap<String, LinkedHashSet<String>>,
        storageChain: MutableList<org.luckypray.dexkit.result.ClassData>
    ) {
        fun add(key: String, names: Collection<String>) {
            if (names.isEmpty()) return
            acc.getOrPut(key) { LinkedHashSet() }.addAll(names)
        }

        // ① 会话对象类（k4）：靠「含 field_username / field_flag」这两个数据库列定位。
        //    ⚠️ 用 findClass 的 fields 匹配而不是 findField：实测 findField 的
        //    searchPackages 在只扫 base.apk 时命中不到（8.0.78 真机），
        //    而 findClass + matcher.fields 是同一套求值路径，稳定。
        for (probe in listOf("field_username", "field_talker", "field_flag")) {
            val cls = try {
                bridge.findClass {
                    searchPackages("com.tencent.mm.storage")
                    matcher { fields { add { name = probe } } }
                }.firstOrNull()
            } catch (t: Throwable) {
                Log.w(TAG, "会话对象类反查($probe) 失败: ${t.message}")
                null
            }
            if (cls != null) {
                add("Storage", listOf(cls.name))
                // 记住类本身：字段名直接从它（含父类）上枚举，比「按名字全局搜」可靠得多
                if (storageChain.none { it.name == cls.name }) storageChain.add(cls)
            }
        }

        // ② 会话适配器（ListView 分支）：声明 getItem(int) 且返回「会话对象家族」的类。
        //    这比「getView 签名」精确得多：8.0.78 上是 jo5.y0，8.0.79 是 iq5.a1，
        //    两者都满足这个特征；而 getView 在微信里能扫出 140+ 个。
        //
        //    ⚠️ 不能用「返回类型 == ① 反查到的那个类」来卡：8.0.78 真机上
        //    字段在基类 com.tencent.mm.storage.g 上，而 getItem 声明返回子类
        //    com.tencent.mm.storage.k4 —— 精确比对会漏掉真正的适配器。
        //    所以这里按「返回类型落在 com.tencent.mm.storage 包」过滤。
        add("ConversationAdapter", findMethods(bridge, "com.tencent.mm") {
            name = "getItem"
            paramTypes("int")
        }.filter { it.returnTypeName.startsWith("com.tencent.mm.storage") }
            .map { it.declaredClassName })
        // 兜底：BaseAdapter + getView(+getItem) 结构特征。
        //    单独 getView 在微信里能扫出 140+ 个（各种列表都有），所以这里尽量把
        //    getItem(int) 也一起卡上（DexKit 的 methods 多个 add 是「与」语义）。
        add("ConversationAdapterFallback2", findClasses(bridge, "com.tencent.mm") {
            superClass = "android.widget.BaseAdapter"
            methods {
                add {
                    name = "getView"
                    paramTypes("int", "android.view.View", "android.view.ViewGroup")
                    returnType = "android.view.View"
                }
                add { name = "getItem"; paramTypes("int") }
            }
        })
        add("ConversationAdapterFallback", findClasses(bridge, "com.tencent.mm") {
            superClass = "android.widget.BaseAdapter"
            methods {
                add {
                    name = "getView"
                    paramTypes("int", "android.view.View", "android.view.ViewGroup")
                    returnType = "android.view.View"
                }
            }
        })

        // ③ 会话适配器（RecyclerView 分支）：部分机型/版本走这条链
        add("ConversationAdapterRv", findClasses(bridge, "com.tencent.mm") {
            superClass = "androidx.recyclerview.widget.RecyclerView\$Adapter"
            methods { add { name = "getItemCount"; returnType = "int" } }
        })

        // ④ 会话长按菜单宿主：onCreateContextMenu 的候选 ∩ onItemLongClick 的候选。
        //    单独用 onCreateContextMenu 在会话包里也有 5 个左右（8.0.78 实测），
        //    加上「还得响应列表长按」这条就能收敛到 1 个（8.0.78=…conversation.s3）。
        val menuHosts = findClasses(bridge, "com.tencent.mm.ui.conversation") {
            methods {
                add {
                    name = "onCreateContextMenu"
                    paramTypes(
                        "android.view.ContextMenu",
                        "android.view.View",
                        "android.view.ContextMenu\$ContextMenuInfo"
                    )
                }
            }
        }
        val longClickHosts = findClasses(bridge, "com.tencent.mm.ui.conversation") {
            methods {
                add {
                    name = "onItemLongClick"
                    paramTypes("android.widget.AdapterView", "android.view.View", "int", "long")
                    returnType = "boolean"
                }
            }
        }
        add("ConversationMenuListener", menuHosts intersect longClickHosts)
        add("ConversationMenuListenerWide", menuHosts)

        // ⑤ 菜单回调接口：声明 onMMMenuItemSelected 的类（两种参数顺序都试）。
        //    全包扫会命中 500+ 个（这个回调名在微信里到处都是），所以先限定会话包
        //    （8.0.78 实测就是 …conversation.q3），会话包没有再退回全包。
        fun menuCallback(pkg: String): List<String> =
            listOf(
                listOf("android.view.MenuItem", "int"),
                listOf("int", "android.view.MenuItem")
            ).flatMap { ps ->
                findMethods(bridge, pkg) {
                    name = "onMMMenuItemSelected"
                    paramTypes(ps[0], ps[1])
                }.map { it.declaredClassName }
            }
        val cbConv = menuCallback("com.tencent.mm.ui.conversation")
        add("ConversationMenuCallback", cbConv.takeIf { it.isNotEmpty() } ?: menuCallback("com.tencent.mm"))

        // ⑥ 字段名：直接把会话对象类（含父类）的字段全部枚举出来再挑。
        //    为什么不用「按名字全局搜」：8.0.78 上 field_flag / field_unReadCount
        //    运行时确实存在（置顶与未读都取到了值），但按名字搜一无所获 ——
        //    DexKit 的字段索引在这份 dex 上不认这两个名字。枚举反而能看到真名，
        //    万一哪天被改名也能靠「类型 + 名字关键字」认出来。
        if (storageChain.isEmpty()) return
        val all = LinkedHashMap<String, String>()   // 字段名 → 类型
        for (cls in storageChain) {
            var c: org.luckypray.dexkit.result.ClassData? = cls
            var depth = 0
            while (c != null && depth < 8) {
                runCatching {
                    for (f in c!!.fields) all.putIfAbsent(f.name, f.typeName)
                }
                c = runCatching { c!!.superClass }.getOrNull()
                depth++
            }
        }
        add("fieldScan", listOf(all.entries.take(60).joinToString(",") { "${it.key}:${it.value}" }))
        for (probe in FIELD_PROBES) {
            val hit = probe.resolve(all)
            if (hit != null) {
                add("field:${probe.key}", listOf(hit))
                add("fieldHit:${probe.key}", listOf(hit))
            }
        }
    }

    /** 一个逻辑字段的识别规则 */
    private data class FieldProbe(
        val key: String,
        /** 已知候选名，按可信度排序 */
        val names: List<String>,
        /** 期望类型；空表示不限（JVM 描述符与完整类名都接受） */
        val types: Set<String>,
        /** 名字里包含任一关键字即可（全小写比较），用于字段名被改名的兜底 */
        val keywords: List<String>
    ) {
        /** 在 (字段名 → 类型) 表里面挑出这个逻辑字段的真实名字 */
        fun resolve(fields: Map<String, String>): String? {
            fields.entries.firstOrNull { (n, t) ->
                names.contains(n) && (types.isEmpty() || types.contains(t))
            }?.let { return it.key }
            return fields.entries.firstOrNull { (n, t) ->
                (types.isEmpty() || types.contains(t)) &&
                    keywords.any { n.lowercase().contains(it) }
            }?.key
        }
    }

    private val STR = setOf("java.lang.String")
    private val LONG = setOf("long", "J")
    private val INT = setOf("int", "I")

    private val FIELD_PROBES = listOf(
        FieldProbe("username", listOf("field_username", "field_talker"), STR, listOf("username", "talker")),
        FieldProbe("flag", listOf("field_flag"), LONG, listOf("flag")),
        FieldProbe("unread", listOf("field_unReadCount", "field_unreadCount"), INT, listOf("unread")),
        FieldProbe("remark", listOf("field_conRemark"), STR, listOf("remark")),
        FieldProbe("nickname", listOf("field_nickname", "field_nickName"), STR, listOf("nickname", "nick"))
    )

    private fun findClasses(
        bridge: DexKitBridge,
        pkg: String,
        block: org.luckypray.dexkit.query.matchers.ClassMatcher.() -> Unit
    ): Set<String> = try {
        bridge.findClass {
            searchPackages(pkg)
            matcher { block() }
        }.map { it.name }.toSet()
    } catch (t: Throwable) {
        Log.w(TAG, "findClass($pkg) 失败: ${t.message}")
        emptySet()
    }

    private fun findMethods(
        bridge: DexKitBridge,
        pkg: String,
        block: org.luckypray.dexkit.query.matchers.MethodMatcher.() -> Unit
    ): List<org.luckypray.dexkit.result.MethodData> = try {
        bridge.findMethod {
            searchPackages(pkg)
            matcher { block() }
        }.toList()
    } catch (t: Throwable) {
        Log.w(TAG, "findMethod($pkg) 失败: ${t.message}")
        emptyList()
    }

    // ==================================================================================
    // 汇总：候选 → 结论
    // ==================================================================================

    // ==================================================================================
    // 运行时实测：Hook 侧回传的「真的取到值」的类名 / 字段名
    // ==================================================================================

    /** 运行时实测键 → 签名里的落点（类名键或 field:字段名） */
    private val RUNTIME_KEYS = linkedMapOf(
        "adapter" to "ConversationAdapter",
        "storage" to "Storage",
        "menu" to "ConversationMenuListener",
        "username" to "field:username",
        "flag" to "field:flag",
        "unread" to "field:unread"
    )

    /**
     * 从运行日志镜像里读 Hook 侧回传的实测值。
     *
     * 静态反查拿不准的三项（会话适配器 / field_flag / field_unReadCount）全靠它：
     * 那三项在 dex 里长得和别的一模一样（一百多个 BaseAdapter 子类）或压根被
     * DexKit 的字段索引认不出，但 Hook 在微信进程里确实从它们取到了值。
     */
    private fun readRuntimeProbe(): Map<String, String> {
        val lines = RunLog.readMirror()?.lines ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf("运行时实测")
            if (idx < 0) continue
            val body = line.substring(line.indexOf(':', idx) + 1)
            for (kv in body.split(' ')) {
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                out.putIfAbsent(kv.substring(0, eq), kv.substring(eq + 1))
            }
        }
        return out
    }

    /** 把候选收敛成结论。[runtime]（Hook 侧实测）优先级最高，静态反查只能是补充 */
    private fun finalize(acc: Map<String, LinkedHashSet<String>>, runtime: Map<String, String>): List<Finding> {
        val out = mutableListOf<Finding>()

        fun pick(key: String, label: String, note: String = ""): Finding {
            val c = acc[key]?.toList() ?: emptyList()
            return when {
                c.size == 1 -> Finding(key, label, c[0], Level.OK, c, note)
                c.size > 1 -> Finding(key, label, null, Level.AMBIGUOUS, c, note)
                else -> Finding(key, label, null, Level.MISSING, c, note)
            }
        }

        out.add(pick("Storage", "会话对象类", "声明了 field_username / field_flag 的存储类"))

        // 会话适配器（ListView）：三档证据，取第一个能收敛到唯一答案的。
        //   A. getItem(int) 返回「com.tencent.mm.storage.*」  —— 最强
        //   B. BaseAdapter + getView + getItem
        //   C. BaseAdapter + getView                          —— 最弱，微信里有一百多个
        val a = acc["ConversationAdapter"]?.toList() ?: emptyList()
        val b = acc["ConversationAdapterFallback2"]?.toList() ?: emptyList()
        val c = acc["ConversationAdapterFallback"]?.toList() ?: emptyList()
        out.add(
            when {
                a.size == 1 -> Finding("ConversationAdapter", "会话适配器(ListView)", a[0], Level.OK, a, "getItem(int) 返回会话对象家族")
                b.size == 1 -> Finding("ConversationAdapter", "会话适配器(ListView)", b[0], Level.OK, b, "BaseAdapter + getView + getItem")
                a.size > 1 -> Finding("ConversationAdapter", "会话适配器(ListView)", null, Level.AMBIGUOUS, a, "getItem(int) 返回会话对象家族")
                b.size > 1 -> Finding("ConversationAdapter", "会话适配器(ListView)", null, Level.AMBIGUOUS, b, "BaseAdapter + getView + getItem")
                else -> Finding("ConversationAdapter", "会话适配器(ListView)", null, Level.MISSING, c, "BaseAdapter + getView（弱特征，候选过多不采用）")
            }
        )

        out.add(pick("ConversationAdapterRv", "会话适配器(RecyclerView)"))
        out.add(pick("ConversationMenuListener", "长按菜单宿主"))
        out.add(pick("ConversationMenuCallback", "菜单回调接口"))
        for (probe in FIELD_PROBES) {
            out.add(pick("field:${probe.key}", "字段 ${probe.key}"))
        }
        // 会话对象类的字段全表（截断）—— 纯排查用，不写进签名
        acc["fieldScan"]?.firstOrNull()?.let { scan ->
            out.add(
                Finding(
                    "fieldScan", "会话对象类字段（排查用）", scan.take(200),
                    Level.OK, emptyList(), "名字:类型 清单，用来核对字段名有没有被改名"
                )
            )
        }

        // ── 运行时实测覆盖静态结论 ──
        // Hook 侧真的从这些类/字段取到过值，比任何静态启发式都可信。
        for ((rk, target) in RUNTIME_KEYS) {
            val v = runtime[rk] ?: continue
            val i = out.indexOfFirst { it.key == target }
            val merged = Finding(
                key = target,
                label = if (i >= 0) out[i].label else target,
                value = v,
                level = Level.OK,
                candidates = listOf(v),
                note = "运行时实测（Hook 在微信进程里真实取到值）"
            )
            if (i >= 0) out[i] = merged else out.add(merged)
        }
        return out
    }

    /** 允许写进签名 classes 的键（其余的多候选/诊断项只展示，不落签名） */
    private val CLASS_KEYS = setOf(
        "Storage", "ConversationAdapter", "ConversationAdapterRv",
        "ConversationMenuListener", "ConversationMenuCallback"
    )

    // ==================================================================================
    // 组装签名 JSON
    // ==================================================================================

    /**
     * 以「当前生效签名」为基底，只覆盖反查成功（OK）的项，产出新签名。
     * 返回 null 表示基底都读不出来（内置资源损坏）。
     */
    private fun buildJson(ctx: Context, version: String, findings: List<Finding>): String? {
        val baseJson = GroupStore.effectiveSignatureJson(ctx) ?: return null
        val base = try {
            WeChatSignatures.fromJson(JSONObject(baseJson))
        } catch (t: Throwable) {
            Log.w(TAG, "基底签名解析失败: ${t.message}")
            null
        } ?: return null

        val classes = base.classes.toLinkedMap()
        val fields = base.fields.toLinkedMap()
        val applied = mutableListOf<String>()
        for (f in findings) {
            if (f.level != Level.OK || f.value == null) continue
            when {
                f.key.startsWith("field:") -> fields[f.key.removePrefix("field:")] = f.value
                f.key in CLASS_KEYS -> classes[f.key] = f.value
                else -> continue   // 多候选 / 诊断项不落签名
            }
            applied.add(f.key)
        }

        val sig = base.copy(
            wechatVersion = version,
            classes = classes,
            fields = fields,
            revision = ANALYZED_REVISION_BASE
        )
        return try {
            val root = sig.toJson()
            // 附分析记录，便于回看/排查：不参与运行时解析，纯信息
            root.put("_analysis", JSONObject().apply {
                put("generatedAt", System.currentTimeMillis())
                put("generatedBy", "SignatureAnalyzer v1")
                put("applied", JSONArray(applied))
                put("findings", JSONArray().also { arr ->
                    findings.forEach { f ->
                        arr.put(JSONObject().apply {
                            put("key", f.key)
                            put("label", f.label)
                            put("level", f.level.name)
                            put("value", f.value ?: "")
                            put("candidates", JSONArray(f.candidates))
                        })
                    }
                })
            })
            root.toString(2)
        } catch (t: Throwable) {
            Log.e(TAG, "组装签名 JSON 失败: ${t.message}")
            null
        }
    }

    private fun <K, V> Map<K, V>.toLinkedMap(): LinkedHashMap<K, V> = LinkedHashMap(this)
}
