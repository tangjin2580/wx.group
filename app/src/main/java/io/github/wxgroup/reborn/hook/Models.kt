package io.github.wxgroup.reborn.hook

import android.widget.TextView
import io.github.wxgroup.reborn.core.WxGroup

/** 虚拟行类型：0 = 收纳组头部行，1 = 真实会话行 */
internal const val TYPE_HEADER = 0
internal const val TYPE_REAL = 1

/** 收纳组行专用的 view type（真实会话行的 view type 恒为 0） */
internal const val LEGACY_HEADER_VIEW_TYPE = 1

/** RecyclerView 路径的分组行 view type（负数，避免与内容区类型撞车） */
internal const val RV_FOLDER_VIEW_TYPE = -20240922

/** WxRecyclerAdapter 的类名跨版本稳定 */
internal const val RV_DEFAULT_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

/** 会话行模型里「会话对象」的类型名（8.0.78 为 com.tencent.mm.storage.k4） */
internal const val K4_TYPE = "com.tencent.mm.storage.k4"

/** 虚拟行。memberCount 仅分组行有意义（该分组有多少成员确实在会话列表里） */
internal data class VEntry(
    val type: Int,
    val group: WxGroup? = null,
    val realPos: Int = -1,
    val memberCount: Int = 0
)

/** ListView 分支的虚拟模型 */
internal class LegacyModel(val entries: List<VEntry>, val hiddenCount: Int)

/** RecyclerView 分支的虚拟模型 */
internal class RvModel(
    val realCount: Int,
    val key: String,
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
    var groupId: String? = null
)
