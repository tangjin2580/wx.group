package io.github.wxgroup.reborn.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：Hook 侧（微信进程）的环形缓冲 + /data/local/tmp 镜像落盘，供模块 App 查看。
 *
 * ── 为什么不走 logcat ──
 * App 读不了 logcat（READ_LOGS 是签名级权限），而排查「签名失配 / Hook 没挂上」
 * 最需要的就是看微信进程里的日志。微信进程对 /data/local/tmp 已存在的 666 文件
 * 可覆盖写（分组/设置镜像同一条通道），所以把日志也落在这里，App 直接读文件。
 *
 * ── 写入协议 ──
 * 文件是 JSON：{"savedAt":毫秒, "clearedAt":毫秒, "lines":["10-23 19:21:39.243 I  ...", ...]}
 *   - savedAt  ：Hook 侧最后一次落盘时间（App 显示「落盘于」用）
 *   - clearedAt：App 侧「清空」的时间戳。Hook 侧 flush 时发现文件里的 clearedAt
 *                比自己见过的更新 → 清掉内存缓冲再写，否则清了也会被下一轮 flush 原样写回。
 *
 * ── 权限现实 ──
 * 微信进程在 SELinux 下**建不了** /data/local/tmp 里的新文件，只能覆盖已存在的 666 文件，
 * 所以首次需要 root 预创建（GroupStore.provisionMirrorsWithRoot 已包含本文件）；
 * 没创建时写失败会自动退避（30s 内不重试），日志只留在内存缓冲里，功能不受影响。
 */
object RunLog {

    /** 日志镜像文件（与 groups/settings 同目录同权限模型） */
    const val TMP_LOG = "/data/local/tmp/wxgroup_reborn_log.json"

    /** 内存缓冲上限：超出丢最老的（排查现场够用，且控制镜像文件体积） */
    private const val CAP = 400

    /** flush 去抖：日志经常一秒内爆几十条，没必要每条都写一次文件 */
    private const val FLUSH_DEBOUNCE_MS = 800L

    /** 写失败退避：文件不存在 / SELinux 拒绝时，30s 内不重复尝试 */
    private const val WRITE_BACKOFF_MS = 30_000L

    private const val TAG = "WxGroupReborn"

    private val buf = ArrayDeque<String>(CAP)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 是否处于 Hook 进程（XposedBridge.attach 时置 true）；模块 App 只读不写 */
    @Volatile
    internal var remoteEnabled = false
        private set

    /** Hook 侧最近一次见到的 clearedAt（清空同步） */
    @Volatile
    private var lastSeenClearAt = 0L

    /** 上次 flush 尝试时间（去抖） */
    @Volatile
    private var lastFlushAt = 0L

    /** 写失败退避截止时间 */
    @Volatile
    private var writeBlockedUntil = 0L

    /** 单线程后台落盘，避免在 Hook 回调（主线程）里做 IO */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wxgroup-runlog").apply { isDaemon = true }
    }

    /** 由 XposedBridge.attach 调用：进入 Hook 进程，开始缓冲 */
    fun enableRemote() {
        remoteEnabled = true
    }

    /** 记一条日志（Hook 进程内）；App 进程不会走到这（remoteEnabled=false 时直接忽略） */
    fun record(level: String, msg: String) {
        if (!remoteEnabled) return
        val line = "${timeFmt.format(Date())} $level $msg"
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(line)
        }
        maybeFlush()
    }

    /** 当前内存缓冲快照（测试 / 调试用） */
    fun snapshotLines(): List<String> = synchronized(buf) { buf.toList() }

    /**
     * 去抖落盘：距上次尝试超过 [FLUSH_DEBOUNCE_MS] 才真正提交后台任务。
     * 任何线程都能调，落盘永远在 io 线程。
     */
    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_DEBOUNCE_MS) return
        lastFlushAt = now
        try {
            io.execute { flush() }
        } catch (_: Throwable) {
        }
    }

    /** 立即落盘（App 请求 / 测试用；提交到 io 线程） */
    fun flushNow() {
        if (!remoteEnabled) return
        try {
            io.execute { flush() }
        } catch (_: Throwable) {
        }
    }

    private fun flush() {
        val f = java.io.File(TMP_LOG)
        // 清空同步：App 刚清过 → 内存缓冲一起丢，否则下一轮 flush 又写回去
        val mirror = readMirror()
        if (mirror != null && mirror.clearedAt > lastSeenClearAt) {
            lastSeenClearAt = mirror.clearedAt
            synchronized(buf) { buf.clear() }
        }
        val lines = snapshotLines()
        if (lines.isEmpty() && mirror == null) return  // 没东西写且文件也不存在，别白试
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("clearedAt", lastSeenClearAt)
            .put("lines", JSONArray(lines))
        // 退避期内不重试（文件不存在时写一次失败一次，别每条日志都撞 SELinux）
        if (System.currentTimeMillis() < writeBlockedUntil) return
        try {
            // 直接覆盖写（同目录建不了 .tmp，原子 rename 不可用，见 GroupStore.atomicWriteText 注释）
            f.writeText(json.toString())
        } catch (t: Throwable) {
            writeBlockedUntil = System.currentTimeMillis() + WRITE_BACKOFF_MS
            Log.e(TAG, "运行日志落盘失败 ${f.path}: ${t.message}（需 root 预创建该文件，详见 README）")
        }
    }

    // ===== 读取 / 清空（模块 App 侧用；Hook 侧 flush 也借道 readMirror 做清空同步） =====

    /** 镜像文件快照；文件不存在 / JSON 非法返回 null */
    fun readMirror(): Snapshot? {
        val f = java.io.File(TMP_LOG)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("lines") ?: return null
            val lines = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) lines.add(arr.optString(i))
            Snapshot(
                savedAt = root.optLong("savedAt", 0L),
                clearedAt = root.optLong("clearedAt", 0L),
                lines = lines
            )
        } catch (t: Throwable) {
            // 空文件（刚 touch 出来）会走到这，属正常
            null
        }
    }

    /** 清空镜像（App 侧「清空」按钮）：写空表并盖上 clearedAt，Hook 侧下次 flush 会同步清内存缓冲 */
    fun clearMirror(): Boolean = try {
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("clearedAt", System.currentTimeMillis())
            .put("lines", JSONArray())
        java.io.File(TMP_LOG).writeText(json.toString())
        true
    } catch (t: Throwable) {
        Log.e(TAG, "清空运行日志失败: ${t.message}")
        false
    }

    /** 镜像文件是否可写（App 侧用来决定要不要提示 root 预创建） */
    fun mirrorReady(): Boolean = java.io.File(TMP_LOG).let { it.exists() && it.canWrite() }

    /** 一条「root 预创建」提示命令，直接可复制进 adb */
    fun provisionHint(): String =
        "adb shell su -c 'touch $TMP_LOG && chmod 666 $TMP_LOG'"

    data class Snapshot(val savedAt: Long, val clearedAt: Long, val lines: List<String>)
}
