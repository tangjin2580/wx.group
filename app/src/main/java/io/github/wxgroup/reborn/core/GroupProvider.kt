package io.github.wxgroup.reborn.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨进程数据桥：模块 App（设置界面）把分组/签名写在自己的 SharedPreferences，
 * 微信进程通过 ContentResolver.query(...) 向本 Provider 索取。
 *
 * 关键点：Android 11+ 的 AppsFilter 默认禁止微信「看见」本模块 App 的 Provider，
 * 因此在 <application> 上声明 android:forceQueryable="true" 并在 Provider 上
 * android:exported="true"，让该 Provider 对所有包可见、可解析，从而绕过 AppsFilter。
 * Provider 运行在模块 App 进程，天然可读写本 App 私有数据，再由 Provider 协议回传微信。
 *
 * 只回应微信与本模块自己的查询（见 callerAllowed）。
 */
class GroupProvider : ContentProvider() {

    private val TAG = "WxGroupReborn"
    private val AUTHORITY = "io.github.wxgroup.groups"
    private val PATH_GROUPS = "groups"
    private val PATH_SIGNATURE = "signature"
    private val COL_DATA = "data"
    private val WECHAT_PKG = "com.tencent.mm"

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        // exported 是为了让微信能查；其它 App 一律拒绝，分组里全是联系人 / 群的 wxid
        if (!callerAllowed()) return null
        val data = try {
            when (uri.lastPathSegment) {
                PATH_GROUPS -> ctx.getSharedPreferences(GroupStore.PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(GroupStore.KEY_GROUPS, null)
                    ?: JSONObject().put("groups", JSONArray()).toString()
                PATH_SIGNATURE -> GroupStore.getSignatures(ctx)?.toJson()?.toString()
                    ?: GroupStore.embeddedSignatureJson() ?: ""
                else -> ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Provider.query(${uri.lastPathSegment}) 失败: ${t.message}")
            ""
        }
        val c = MatrixCursor(arrayOf(COL_DATA))
        c.addRow(arrayOf(data))
        return c
    }

    private fun callerAllowed(): Boolean {
        val pkg = callingPackage ?: return false
        return pkg == WECHAT_PKG || pkg == context?.packageName
    }

    // 以下为 ContentProvider 必须实现的空方法（本模块仅用 query 通道）
    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/$AUTHORITY"
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sel: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sel: Array<out String>?): Int = 0
}
