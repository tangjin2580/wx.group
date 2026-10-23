package io.github.wxgroup.reborn.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.wxgroup.reborn.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：拉 GitHub Releases 的 latest，用语义化版本号比较，有新版本弹窗。
 *
 * 不引入任何网络库 —— 用 JDK 自带的 [HttpURLConnection]，包体零增长。
 * 仓库名与 git remote 保持一致（`tangjin2580/wx.group`，注意点号是仓库名的一部分）。
 */
object Updater {

    private const val REPO = "tangjin2580/wx.group"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class ReleaseInfo(
        val tag: String,         // 原始 tag，如 "v1.0.0"
        val version: String,     // 去掉前导 v，如 "1.0.0"
        val name: String,
        val htmlUrl: String,
        val downloadUrl: String, // APK 资源下载地址；取不到就回退到发布页
        val publishedAt: String,
        val body: String
    )

    fun currentVersion(ctx: android.content.Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** 拉取最新 release；网络/解析失败返回 null */
    fun fetchLatest(): ReleaseInfo? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 要求带 User-Agent，否则直接 403
            setRequestProperty("User-Agent", "wxgroup-reborn-updater")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val tag = j.optString("tag_name", "")
            val version = tag.removePrefix("v").trim()
            if (version.isEmpty()) return null
            val name = j.optString("name", tag)
            val htmlUrl = j.optString("html_url", RELEASES_PAGE)
            val body = j.optString("body", "")
            val publishedAt = j.optString("published_at", "")
            // 取第一个 .apk 附件作为下载地址；没有就用发布页（用户自己找附件）
            var downloadUrl = ""
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (downloadUrl.isEmpty()) downloadUrl = htmlUrl
            return ReleaseInfo(tag, version, name, htmlUrl, downloadUrl, publishedAt, body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 语义化版本比较：remote 比 current 新返回 >0，相同 0，更旧 <0。
     * 容错：去掉前导 v，按数字段比较，遇到非数字段忽略；段数不等时短的补 0。
     */
    fun compareVersion(remote: String, current: String): Int {
        val rp = parseVersion(remote)
        val cp = parseVersion(current)
        val n = maxOf(rp.size, cp.size)
        for (i in 0 until n) {
            val a = rp.getOrElse(i) { 0 }
            val b = cp.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> {
        val head = v.removePrefix("v").split(".", "-", "+")[0]
        return head.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
    }

    /**
     * 检查更新。
     * @param manual 手动触发：无论结果都给 toast；自动检查只在有新版本时弹窗（不打扰）。
     */
    fun check(activity: Activity, manual: Boolean) {
        if (manual) {
            Toast.makeText(activity, activity.getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        }
        Thread {
            var rel: ReleaseInfo? = null
            var err: String? = null
            try {
                rel = fetchLatest()
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            Handler(Looper.getMainLooper()).post {
                when {
                    rel != null -> onResult(activity, rel, manual)
                    manual -> Toast.makeText(
                        activity,
                        activity.getString(R.string.update_fail, err ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun onResult(activity: Activity, rel: ReleaseInfo, manual: Boolean) {
        val cur = currentVersion(activity)
        if (compareVersion(rel.version, cur) > 0) {
            showUpdateDialog(activity, rel, cur)
        } else if (manual) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_latest, cur),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showUpdateDialog(activity: Activity, rel: ReleaseInfo, cur: String) {
        val notes = rel.body.trim().let { if (it.length > 400) it.take(400) + "…" else it }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, rel.version))
            .setMessage(activity.getString(R.string.update_dialog_msg, cur, rel.version, notes))
            .setPositiveButton(R.string.update_btn_download) { _, _ -> openUrl(activity, rel.downloadUrl) }
            .setNeutralButton(R.string.update_btn_view) { _, _ -> openUrl(activity, rel.htmlUrl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            // 没有浏览器之类的情况，静默失败（用户可去发布页手动找）
        }
    }
}
