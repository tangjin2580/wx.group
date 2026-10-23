package io.github.wxgroup.reborn.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 微信签名配置页。
 *
 * 签名获取的三条路（从省事到折腾）：
 *   1. **内置**：APK 的 res/raw/wechat_signatures.json（随版本发布，hook 端默认就用它，
 *      正常情况下这个页面什么都不用改）。状态行会显示「微信版本 vs 内置签名版本」是否匹配。
 *   2. **在线**：从 GitHub 仓库拉最新的 wechat_signatures.json —— 适配新微信时
 *      只需要更新仓库文件，不用发新版 App。
 *   3. **手动**：自己用 jadx 逆向对应版本微信，把类/方法签名填进编辑器。
 *
 * 「填充内置」「在线获取」都只落到编辑器，不直接保存 —— 用户确认无误再点保存。
 */
class SignatureActivity : AppCompatActivity() {

    private lateinit var etJson: EditText
    private lateinit var tvStatus: TextView

    /** 在线签名源：url + 可选 Basic 认证（私有 WebDAV 用） */
    private data class OnlineSource(val url: String, val user: String? = null, val pass: String? = null)

    /**
     * 在线签名源的候选列表（按可达性排序，逐个回退）。
     *
     * ① r2pan WebDAV —— 自建网盘镜像，Cloudflare 加速，国内直连最稳；
     * ② jsDelivr CDN —— GitHub 仓库的国内友好镜像；
     * ③ GitHub API contents（raw media type）—— api.github.com 国内一般可达；
     * ④ raw.githubusercontent.com —— 最后兜底（国内常被墙）。
     *
     * ⚠️ 路径必须是 **assets**：v1.0.1 起签名库从 res/raw 迁到 assets
     * （AGP release 资源路径优化会重命名 res/raw 路径），指向 res/raw 会 404。
     */
    private val onlineSources = listOf(
        OnlineSource(
            "https://r2pan.us.ci/webdav/wxgroup/wechat_signatures.json",
            user = "webdav",
            pass = "lixin2324"
        ),
        OnlineSource("https://cdn.jsdelivr.net/gh/tangjin2580/wx.group@main/app/src/main/assets/wechat_signatures.json"),
        OnlineSource("https://api.github.com/repos/tangjin2580/wx.group/contents/app/src/main/assets/wechat_signatures.json"),
        OnlineSource("https://raw.githubusercontent.com/tangjin2580/wx.group/main/app/src/main/assets/wechat_signatures.json")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature)
        title = getString(R.string.signature_title)

        etJson = findViewById(R.id.et_json)
        tvStatus = findViewById(R.id.tv_status)

        // 优先显示已保存的，否则显示内置默认
        val saved = GroupStore.getSignatures(this)
        etJson.setText(saved?.toJson()?.toString(2) ?: GroupStore.defaultSignatureJson())

        refreshStatus()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { reset() }
        findViewById<Button>(R.id.btn_bundled).setOnClickListener { fillBundled() }
        findViewById<Button>(R.id.btn_fetch).setOnClickListener { fetchOnline() }
    }

    /** 刷新顶部状态行：已装微信版本 vs 内置签名版本 */
    private fun refreshStatus() {
        val wechat = GroupStore.installedWechatVersion(this)
        val bundled = GroupStore.bundledSignatureVersion()
        tvStatus.text = if (wechat == null) {
            getString(R.string.signature_status_mismatch, "未安装", bundled.ifEmpty { "无" })
        } else if (wechat == bundled) {
            getString(R.string.signature_status_match, wechat, bundled)
        } else {
            getString(R.string.signature_status_mismatch, wechat, bundled.ifEmpty { "无" })
        }
    }

    /** 一键填入 APK 内置签名（不自动保存，让用户确认后再点保存） */
    private fun fillBundled() {
        val json = GroupStore.defaultSignatureJson()
        if (json == null) {
            Toast.makeText(this, R.string.signature_fetch_fail, Toast.LENGTH_SHORT).show()
            return
        }
        etJson.setText(json)
        Toast.makeText(this, R.string.signature_fill_done, Toast.LENGTH_LONG).show()
    }

    /**
     * 从 GitHub 仓库拉最新签名（适配新微信无需发版）。网络请求放后台线程。
     * 三级源逐个回退，任一成功即止；全失败时把每个源的失败原因汇总提示。
     */
    private fun fetchOnline() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            var text: String? = null
            val errors = ArrayList<String>()
            for (src in onlineSources) {
                val host = try { java.net.URL(src.url).host } catch (_: Throwable) { src.url }
                try {
                    val conn = (URL(src.url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "wxgroup-reborn-signatures")
                        // 私有 WebDAV：Basic 认证（凭据随源配置）
                        if (src.user != null) {
                            val token = android.util.Base64.encodeToString(
                                "${src.user}:${src.pass}".toByteArray(),
                                android.util.Base64.NO_WRAP
                            )
                            setRequestProperty("Authorization", "Basic $token")
                        }
                        // GitHub API 的 contents 接口：指定 raw media type 可直接拿到文件原文
                        if (host == "api.github.com") {
                            setRequestProperty("Accept", "application/vnd.github.raw")
                        }
                    }
                    try {
                        val code = conn.responseCode
                        if (code == HttpURLConnection.HTTP_OK) {
                            text = conn.inputStream.bufferedReader().use { it.readText() }
                        } else {
                            errors.add("$host HTTP $code")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (t: Throwable) {
                    errors.add("$host ${t.message ?: t.javaClass.simpleName}")
                }
                if (text != null) break
            }
            android.util.Log.i("WgSigFetch", "fetchOnline: ok=${text != null} errors=$errors")
            Handler(Looper.getMainLooper()).post {
                val json = text
                when {
                    // 校验：必须是合法 JSON 且带 methods 字段，避免把错误页存进配置
                    json != null && isValidJson(json) && JSONObject(json).has("methods") -> {
                        etJson.setText(json)
                        val v = JSONObject(json).optString("wechatVersion", "?")
                        Toast.makeText(
                            this, getString(R.string.signature_fetch_ok, v), Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> Toast.makeText(
                        this,
                        getString(R.string.signature_fetch_fail, errors.joinToString("; ")),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun save() {
        val text = etJson.text.toString()
        if (!isValidJson(text)) {
            Toast.makeText(this, R.string.signature_bad_json, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = GroupStore.saveSignature(this, text)
        Toast.makeText(this, if (ok) R.string.signature_saved else R.string.signature_bad_json, Toast.LENGTH_SHORT).show()
    }

    private fun reset() {
        GroupStore.resetSignature(this)
        etJson.setText(GroupStore.defaultSignatureJson())
        Toast.makeText(this, R.string.signature_saved, Toast.LENGTH_SHORT).show()
    }

    private fun isValidJson(text: String): Boolean = try {
        JSONObject(text); true
    } catch (t: Throwable) {
        false
    }
}
