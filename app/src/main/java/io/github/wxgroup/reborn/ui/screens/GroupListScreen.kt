package io.github.wxgroup.reborn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupNameRules
import io.github.wxgroup.reborn.ui.components.DragSortList
import io.github.wxgroup.reborn.ui.components.EmptyState
import io.github.wxgroup.reborn.ui.components.IconBox
import io.github.wxgroup.reborn.ui.components.WxCard
import io.github.wxgroup.reborn.ui.components.clickableOnce
import io.github.wxgroup.reborn.ui.state.GroupUi
import io.github.wxgroup.reborn.ui.state.GroupsUiState
import io.github.wxgroup.reborn.ui.theme.BadgeRed
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxShape
import io.github.wxgroup.reborn.ui.theme.WxSize
import io.github.wxgroup.reborn.ui.theme.WxSpace

/* ══════════════════════════════════════════════════════════════════════════════
 * 分组管理
 *
 * ── 信息层级（一行分组只讲四件事，按重要性从左到右排）──
 *   1) 图标 + 名称（主信息，17sp，置顶时加粗）
 *   2) 辅助信息一行：成员数 · 未读数 · 免打扰（13sp 次要色）
 *   3) 未读角标（红色胶囊，只在 >0 时出现）
 *   4) 操作：免打扰 / 置顶 / 拖动手柄（全部 48dp 触摸区，图标 20dp）
 *
 * ── 手势分工（互不打架）──
 *   左右滑动 → 重命名 / 删除（SwipeToDismissBox，动作后弹回，不让条目消失）
 *   点击行   → 进入成员管理
 *   长按拖动 → 排序（只挂在右侧把手上，不占用整行的长按，避免和「点击」抢事件）
 *
 * ── 删除 = 确认 + 可撤销 ──
 * 删除前必须确认（显示真实名称与数据影响）；确认后立即给出含「撤销」的 Snackbar，
 * 撤销会把分组连同位置与自定义色原样放回 —— 误删不用靠备份救。
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 名称输入对话框的目标（null = 新建；非 null = 重命名） */
private data class NameEditTarget(val groupId: String?, val initial: String)

/** 待删除的目标（只存 id + 名字，避免列表刷新后拿着旧对象） */
private data class DeleteTarget(val id: String, val name: String)

@Composable
fun GroupListScreen(
    state: GroupsUiState,
    /** 新建：传入用户填的名称，返回落盘后的名字；null = 名称不合法，未写入任何数据 */
    onCreate: (String) -> String?,
    /** 重命名：(分组 id, 新名称) → 新名字；null = 名称不合法，原分组未改动 */
    onRename: (String, String) -> String?,
    onTogglePin: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onMembers: (String) -> Unit,
    /** 删除：返回被删掉的分组名（用于反馈与撤销）；分组不存在时返回 null */
    onDelete: (String) -> String?,
    onUndoDelete: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRefresh: () -> Unit,
    /** 由外层脚手架提供，删除后在这里弹「撤销」 */
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues
) {
    val colors = LocalAppColors.current
    var nameTarget by remember { mutableStateOf<NameEditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // 拖动过程中暂时禁止横向滑动（手势互斥：拖的时候不响应左右滑）
    var dragging by remember { mutableStateOf(false) }
    // 删除成功后的待办：弹出带「撤销」的 Snackbar
    var pendingUndo by remember { mutableStateOf<String?>(null) }
    // 创建 / 重命名成功后的待办：弹一条结果反馈
    var pendingToast by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val context = LocalContext.current

    // 过滤只在名称上做：分组本来就没别的检索维度
    val filtered = remember(state.groups, query) {
        val q = query.trim()
        if (q.isEmpty()) state.groups else state.groups.filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .padding(contentPadding)
    ) {
        // ── 新建 + 搜索 ──
        Row(
            modifier = Modifier
                .padding(horizontal = WxSpace.lg, vertical = WxSpace.sm)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WxCard(
                modifier = Modifier.weight(1f).clickableOnce(onClick = {
                    nameTarget = NameEditTarget(groupId = null, initial = "")
                })
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = WxSpace.md, vertical = WxSpace.sm + WxSpace.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBox(icon = Icons.Rounded.Add, tint = colors.primary)
                    Spacer(Modifier.width(WxSpace.sm))
                    Text(
                        text = stringResource(R.string.group_add),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.width(WxSpace.sm))
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.group_refresh),
                    tint = colors.textSecondary
                )
            }
        }

        if (state.groups.size > 3) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.padding(horizontal = WxSpace.lg, vertical = WxSpace.xs)
            )
        }

        when {
            state.loading -> LoadingBlock(modifier = Modifier.weight(1f))
            state.error != null -> ErrorState(
                message = state.error,
                onRetry = onRefresh,
                modifier = Modifier.weight(1f)
            )
            state.groups.isEmpty() -> EmptyGroups(
                onCreate = { nameTarget = NameEditTarget(groupId = null, initial = "") },
                modifier = Modifier.weight(1f)
            )
            filtered.isEmpty() -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.group_search_no_match),
                message = stringResource(R.string.group_search_no_match_msg),
                actionLabel = stringResource(R.string.group_search_clear),
                onAction = { query = "" }
            )
            else -> DragSortList(
                items = filtered,
                itemKey = { it.id },
                itemHeight = WxSize.rowGroup,
                onMove = onMove,
                onDragStateChange = { dragging = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { item, _index, isDragging, dragModifier ->
                GroupRow(
                    group = item,
                    dragging = isDragging,
                    swipeEnabled = !dragging,
                    dragModifier = dragModifier,
                    onRename = { nameTarget = NameEditTarget(item.id, item.name) },
                    onTogglePin = { onTogglePin(item.id) },
                    onToggleMute = { onToggleMute(item.id) },
                    onMembers = { onMembers(item.id) },
                    // 左滑只负责「发起」，真正删要过确认框
                    onRequestDelete = { deleteTarget = DeleteTarget(item.id, item.name) }
                )
            }
        }
    }

    // ── 新建 / 重命名：同一个输入框，区别在于回填值与提交目标 ──
    nameTarget?.let { target ->
        NameEditDialog(
            target = target,
            onDismiss = { nameTarget = null },
            onSubmit = { name ->
                // 先关框再回调：框没了就不存在「再点一次提交」
                nameTarget = null
                if (target.groupId == null) {
                    onCreate(name)?.let { pendingToast = it to R.string.group_created }
                } else {
                    onRename(target.groupId, name)?.let { pendingToast = it to R.string.group_renamed }
                }
            }
        )
    }

    // ── 删除：确认 → 执行 → 给撤销 ──
    deleteTarget?.let { target ->
        DeleteConfirmDialog(
            name = target.name,
            onCancel = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                if (onDelete(target.id) != null) pendingUndo = target.name
            }
        )
    }

    // 删除后的撤销条：ActionPerformed = 用户点了「撤销」
    LaunchedEffect(pendingUndo) {
        val name = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = context.getString(R.string.group_deleted, name),
            actionLabel = context.getString(R.string.group_undo),
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) onUndoDelete()
        pendingUndo = null
    }

    LaunchedEffect(pendingToast) {
        val (name, res) = pendingToast ?: return@LaunchedEffect
        snackbarHost.showSnackbar(context.getString(res, name))
        pendingToast = null
    }
}

/* ── 状态页 ──────────────────────────────────────────────────────────────── */

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.primary)
            Spacer(Modifier.height(WxSpace.md))
            Text(
                text = stringResource(R.string.group_loading),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Rounded.ErrorOutline,
            title = stringResource(R.string.group_load_failed),
            message = stringResource(R.string.group_load_failed_msg, message),
            actionLabel = stringResource(R.string.group_retry),
            onAction = onRetry
        )
    }
}

@Composable
private fun EmptyGroups(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Rounded.FolderOff,
            title = stringResource(R.string.group_list_empty),
            message = stringResource(R.string.group_list_empty_msg),
            actionLabel = stringResource(R.string.group_add),
            onAction = onCreate
        )
    }
}

/* ── 搜索框 ──────────────────────────────────────────────────────────────── */

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(text = stringResource(R.string.group_search_hint), color = colors.textSecondary)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(WxSize.iconRow)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.group_search_clear),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(WxSize.iconRow)
                    )
                }
            }
        },
        shape = WxShape.field,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
            focusedContainerColor = colors.cardBg,
            unfocusedContainerColor = colors.cardBg
        )
    )
}

/* ── 分组行 ──────────────────────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: GroupUi,
    dragging: Boolean,
    swipeEnabled: Boolean,
    dragModifier: Modifier,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onMembers: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        // 拖动排序期间禁用左右滑：两种横向/纵向手势同时抢事件会出现「滑到一半突然变成拖拽」
        confirmValueChange = { value ->
            if (!swipeEnabled) return@rememberSwipeToDismissBoxState false
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onRename()   // 右滑：重命名
                SwipeToDismissBoxValue.EndToStart -> onRequestDelete() // 左滑：请求删除
                else -> Unit
            }
            // 返回 false = 弹回原位：这里「滑动 = 触发动作」，不是「删掉这一行」
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = swipeEnabled,
        enableDismissFromEndToStart = swipeEnabled,
        backgroundContent = { SwipeBackground() }
    ) {
        WxCard(
            borderColor = if (dragging) colors.primary else colors.outlineVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WxSize.rowGroup)
                    .padding(horizontal = WxSpace.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Glyph(glyph = group.icon, tint = Color(group.iconTint))
                Spacer(Modifier.width(WxSpace.gapIconText))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onMembers),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (group.pinned) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group.pinned) {
                            MiniTag(text = stringResource(R.string.group_pinned_tag))
                            Spacer(Modifier.width(WxSpace.xs))
                        }
                        Text(
                            text = subtitleOf(group),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (group.unread > 0) {
                    UnreadBadge(count = group.unread)
                    Spacer(Modifier.width(WxSpace.xs))
                }

                RowToggle(
                    onClick = onToggleMute,
                    desc = stringResource(R.string.group_mute),
                    active = group.muted,
                    imageVector = Icons.Rounded.VolumeOff
                )
                RowToggle(
                    onClick = onTogglePin,
                    desc = stringResource(R.string.group_pin),
                    active = group.pinned,
                    imageVector = Icons.Rounded.PushPin
                )

                // 拖动手柄：长按后上下拖到目标位置松手换序。
                // 它是「手势区」不是「按钮」—— 不挂 click，因此不会和行内点击抢事件。
                Box(
                    modifier = dragModifier
                        .size(WxSize.touchCompact)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.group_drag_sort),
                        tint = if (dragging) colors.primary else colors.textSecondary,
                        modifier = Modifier.size(WxSize.iconRow)
                    )
                }
            }
        }
    }
}

/** 副标题：成员数 / 未读 / 免打扰，用「·」分隔，一行说完 */
@Composable
private fun subtitleOf(group: GroupUi): String = buildString {
    append(group.memberCount).append(" 个成员")
    if (group.unread > 0) append(" · ").append(group.unread).append(" 条未读")
    if (group.muted) append(" · ").append("免打扰")
}

/** 滑动露出的背景：两端各一个图标，说明松手会发生什么 */
@Composable
private fun SwipeBackground() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = stringResource(R.string.group_rename),
            tint = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = WxSpace.xl)
                .size(WxSize.iconSmall)
        )
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.group_delete),
            tint = colors.error,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = WxSpace.xl)
                .size(WxSize.iconSmall)
        )
    }
}

/** 分组图标（单色符号 + 该组强调色）—— 与微信里那一行保持同样的观感 */
@Composable
private fun Glyph(glyph: String, tint: Color) {
    Box(
        modifier = Modifier
            .size(WxSize.avatarBox)
            .clip(WxShape.inner)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = tint,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 置顶小标签：用形状 + 文字表达，不靠颜色单独承担信息 */
@Composable
private fun MiniTag(text: String) {
    val colors = LocalAppColors.current
    Surface(shape = WxShape.pill, color = colors.primary.copy(alpha = 0.14f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = WxSpace.xs, vertical = 1.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary
        )
    }
}

/** 未读角标：恒红（用户靠红色识别未读），形状为胶囊 */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .height(WxSize.badgeHeight)
            .width(if (count > 9) WxSize.badgeWidthDouble else WxSize.badgeWidthSingle)
            .clip(RoundedCornerShape(10.dp))
            .background(BadgeRed),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 行内的置顶 / 免打扰开关按钮。
 *
 * 触摸区 48dp、图标 20dp；激活态用主题主色，未激活用次要文字色
 * （不加透明度：alpha 一降，对比度就不够了）。
 */
@Composable
private fun RowToggle(
    onClick: () -> Unit,
    desc: String,
    active: Boolean,
    imageVector: ImageVector
) {
    val colors = LocalAppColors.current
    IconButton(onClick = onClick, modifier = Modifier.size(WxSize.touchMin)) {
        Icon(
            imageVector = imageVector,
            contentDescription = desc,
            tint = if (active) colors.primary else colors.textSecondary,
            modifier = Modifier.size(WxSize.iconRow)
        )
    }
}

/* ── 对话框 ──────────────────────────────────────────────────────────────── */

@Composable
private fun DeleteConfirmDialog(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = LocalAppColors.current.error
            )
        },
        title = { Text(stringResource(R.string.group_delete_confirm_title)) },
        text = { Text(stringResource(R.string.group_delete_confirm_msg, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.group_delete),
                    color = LocalAppColors.current.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/**
 * 名称输入框：新建 / 重命名共用。
 *
 * - 重命名时回填当前名称（[NameEditTarget.initial]）
 * - trim 后为空 / 超长 → 提交按钮禁用 + 行内错误提示，**不再静默失败**
 * - 点取消或返回键 → 一个字都不写
 */
@Composable
private fun NameEditDialog(
    target: NameEditTarget,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by rememberSaveable(target.groupId ?: "new") { mutableStateOf(target.initial) }
    // 错误提示延迟到用户真正输入后再显示：刚打开就红字「名称不能为空」像在骂人
    var showError by rememberSaveable { mutableStateOf(false) }
    val error = GroupNameRules.check(text)
    val colors = LocalAppColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (target.groupId == null) R.string.group_add else R.string.group_rename
                )
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; showError = true },
                singleLine = true,
                isError = showError && error != GroupNameRules.Error.NONE,
                placeholder = { Text(stringResource(R.string.group_name_hint)) },
                supportingText = {
                    // 不合法时给出**具体原因**（以前是点了没反应）
                    if (showError) when (error) {
                        GroupNameRules.Error.BLANK ->
                            Text(
                                stringResource(R.string.group_name_error_blank),
                                color = colors.error
                            )

                        GroupNameRules.Error.TOO_LONG ->
                            Text(
                                stringResource(
                                    R.string.group_name_error_too_long,
                                    GroupNameRules.MAX_LENGTH
                                ),
                                color = colors.error
                            )

                        GroupNameRules.Error.NONE -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = WxShape.field,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outlineVariant
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(GroupNameRules.normalize(text)) },
                enabled = error == GroupNameRules.Error.NONE
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/* ── 少量 UI 副作用的状态：统一放在组合内，避免模块级变量跨实例共享 ── */
