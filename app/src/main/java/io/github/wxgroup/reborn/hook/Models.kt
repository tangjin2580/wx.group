package io.github.wxgroup.reborn.hook

import android.view.View
import android.widget.TextView
import io.github.wxgroup.reborn.core.WxGroup

/** 虚拟行类型：0 = 收纳组行，1 = 真实会话行，2 = 「分组」小节标题 */
internal const val TYPE_HEADER = 0
internal const val TYPE_REAL = 1
internal const val TYPE_SECTION = 2

/** 小节标题行的 tag：用于 ListView 的 convertView 复用判定（它不是会话行、也不是组行） */
internal const val SECTION_TAG = "WxGroupSection"

/**
 * 收纳组行专用的 view type 默认值。
 * 实际值在运行时取「微信自己的 getViewTypeCount」当新 type（见 ListViewPath 的 getViewTypeCount
 * hook），只有拿不到原值时才用这个常量兜底 —— 写死常量在微信将来新增 type 时会撞车。
 */
internal const val LEGACY_HEADER_VIEW_TYPE = 1

/** RecyclerView 路径的分组行 view type（负数，避免与内容区类型撞车） */
internal const val RV_FOLDER_VIEW_TYPE = -20240922

/** WxRecyclerAdapter 的类名跨版本稳定 */
internal const val RV_DEFAULT_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

/** 会话行模型里「会话对象」的类型名（8.0.78 为 com.tencent.mm.storage.k4） */
internal const val K4_TYPE = "com.tencent.mm.storage.k4"

/**
 * 诊断日志总开关。
 *
 * 打开后会额外挂一些「只为排查」的 Hook（把微信点击消费方全挂上、打印菜单点击的完整调用栈等），
 * 有明显开销，也会刷爆 Xposed 日志 —— release 里默认关闭。
 * 排查「点了没反应 / 闪退」时把它改 true 重新打包即可。
 */
internal const val DIAG_LOG = false

/** 虚拟行。memberCount 仅分组行有意义（该分组有多少成员确实在会话列表里） */
internal data class VEntry(
    val type: Int,
    val group: WxGroup? = null,
    val realPos: Int = -1,
    val memberCount: Int = 0
)

/** ListView 分支的虚拟模型 */
internal class LegacyModel(val entries: List<VEntry>, val hiddenCount: Int)

/**
 * 虚拟模型缓存键。
 *
 * 早期用「真实行数 + 全部分组成员的拼接字符串」当 key，而 getCount 每帧都会被调用 ——
 * 滚动时等于每帧拼一个大字符串再比较，GC 压力明显。改成一个三元组：
 * 行数变了、打开了别的分组、或分组数据本身变过（rev）才重建。
 *
 * ⚠️ 但「行数不变」不等于「模型还能用」：模型里存的是**真实下标**，
 * 只要会话顺序变了（置顶/取消置顶、来新消息把会话顶上来），同样的下标就指向了
 * 另一个会话 —— 表现是分组成员被打散回主列表。所以再补一个 [orderStamp] 顺序指纹。
 */
internal data class ModelKey(
    val realCount: Int,
    val openGroupId: String?,
    val groupsRev: Int,
    /** 会话顺序指纹（见 `orderStampOf`）；RV 分支暂不传，默认 0 */
    val orderStamp: Int = 0
)

/** RecyclerView 分支的虚拟模型 */
internal class RvModel(
    val realCount: Int,
    val key: ModelKey,
    val entries: List<VEntry>,
    val perGroupInList: Map<String, Int>
)

/** 分组行的 ViewHolder 附属信息（列表项回收复用后重新绑定） */
internal class FolderHolder(
    val icon: TextView,      // 图标（emoji 头像）
    val name: TextView,
    val sub: TextView,
    val arrow: TextView,
    val unread: TextView,    // 未读角标（红点/数字）
    val back: TextView,      // 打开态左侧返回箭头（常态 GONE）
    val readBtn: TextView,   // 右侧「已读」按钮（有未读时才显示；不挂点击监听，见 FolderRow）
    val bar: View,           // 左侧竖向色条（主题/单组色）
    val content: View,       // 水平内容行（外层 wrapper 里去掉分隔线的那部分）
    val divider: View,       // 行下方 0.5dp 分隔线
    var groupId: String? = null
)
