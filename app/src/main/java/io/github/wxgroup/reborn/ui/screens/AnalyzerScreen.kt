package io.github.wxgroup.reborn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.SignatureAnalyzer
import io.github.wxgroup.reborn.ui.components.PrimaryButton
import io.github.wxgroup.reborn.ui.components.SecondaryButton
import io.github.wxgroup.reborn.ui.components.SectionTitle
import io.github.wxgroup.reborn.ui.components.StatusLevel
import io.github.wxgroup.reborn.ui.components.StatusPill
import io.github.wxgroup.reborn.ui.components.WxCard
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxSpace

/**
 * 内置签名分析器页面。
 *
 * 干什么：读已装微信的 dex（base.apk + Tinker 补丁），用 DexKit 反查会被混淆的
 * **类名与字段名**，产出一份针对当前微信版本的签名，一键应用。用来替代「换版本就得
 * 人肉 jadx 逆向」——8.0.78 上正是因为签名里的类名还在、语义已变，导致分支半死。
 *
 * 边界：这里只负责「跑分析 + 展示结论 + 应用/导出」，反查逻辑全在
 * [SignatureAnalyzer]，数据读写全在 [GroupStore]。页面不认识 dex，也不拼 JSON。
 */
@Composable
fun AnalyzerScreen(
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<SignatureAnalyzer.Report?>(null) }
    // 统一的轻提示通道（复制/应用结果），避免在 Composable 里直接开协程
    var pendingMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingMessage) {
        val msg = pendingMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        pendingMessage = null
    }

    val wechatVersion = remember { GroupStore.installedWechatVersion(context) }
    var cachedVersions by remember { mutableStateOf(GroupStore.analyzedVersions(context)) }

    /** 跑分析：dex 扫描很慢（200MB+），必须后台线程；进度回主线程更新 */
    fun start() {
        if (running) return
        running = true
        report = null
        progress = context.getString(R.string.analyzer_progress_prepare)
        Thread({
            val r = SignatureAnalyzer.analyze(context) { msg ->
                mainHandler.post { progress = msg }
            }
            mainHandler.post {
                running = false
                report = r
                r.error?.let { pendingMessage = it }
            }
        }, "wxgroup-analyzer").apply { isDaemon = true }.start()
    }

    fun applyReport() {
        val r = report
        val v = r?.wechatVersion
        if (r?.json == null || v.isNullOrBlank()) {
            pendingMessage = context.getString(R.string.analyzer_apply_fail)
            return
        }
        val ok = GroupStore.saveAnalyzedSignature(context, v, r.json!!)
        cachedVersions = GroupStore.analyzedVersions(context)
        pendingMessage = context.getString(
            if (ok) R.string.analyzer_applied else R.string.analyzer_apply_fail, v
        )
    }

    fun copyJson() {
        val json = report?.json
        if (json == null) {
            pendingMessage = context.getString(R.string.analyzer_apply_fail)
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("wxgroup-signatures", json))
        pendingMessage = context.getString(R.string.analyzer_copied)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = WxSpace.screen,
            end = WxSpace.screen,
            top = WxSpace.sm,
            bottom = WxSpace.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(WxSpace.betweenCards)
    ) {
        // ── 说明 + 当前环境 ──
        item {
            WxCard {
                Column(Modifier.padding(WxSpace.lg)) {
                    Text(
                        text = stringResource(R.string.analyzer_intro_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(WxSpace.xs))
                    Text(
                        text = stringResource(R.string.analyzer_intro_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    Spacer(Modifier.height(WxSpace.md))
                    MetaLine(
                        label = stringResource(R.string.analyzer_wechat_version),
                        value = wechatVersion ?: stringResource(R.string.analyzer_not_installed)
                    )
                    MetaLine(
                        label = stringResource(R.string.analyzer_cached_versions),
                        value = cachedVersions.takeIf { it.isNotEmpty() }
                            ?.joinToString("、")
                            ?: stringResource(R.string.analyzer_cached_none)
                    )
                }
            }
        }

        // ── 操作 ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(WxSpace.sm)) {
                PrimaryButton(
                    text = stringResource(if (report == null) R.string.analyzer_start else R.string.analyzer_rerun),
                    onClick = ::start,
                    enabled = !running && wechatVersion != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (running) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WxSpace.xs),
                        color = colors.primary,
                        trackColor = colors.outlineVariant
                    )
                    Text(
                        text = progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        if (wechatVersion == null) {
            item {
                WxCard {
                    Text(
                        text = stringResource(R.string.analyzer_no_wechat),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(WxSpace.lg)
                    )
                }
            }
            return@LazyColumn
        }

        report?.let { r ->
            // ── 结论摘要 ──
            item {
                WxCard {
                    Column(Modifier.padding(WxSpace.lg)) {
                        SectionTitle(text = stringResource(R.string.analyzer_result_section))
                        Spacer(Modifier.height(WxSpace.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(WxSpace.xs)) {
                            StatusPill(
                                text = stringResource(R.string.analyzer_count_ok, r.resolved),
                                level = StatusLevel.OK
                            )
                            StatusPill(
                                text = stringResource(R.string.analyzer_count_ambiguous, r.ambiguous),
                                level = StatusLevel.WARN
                            )
                            StatusPill(
                                text = stringResource(R.string.analyzer_count_missing, r.missing),
                                level = StatusLevel.NEUTRAL
                            )
                        }
                        if (r.runtimeUsed > 0) {
                            Spacer(Modifier.height(WxSpace.xs))
                            Text(
                                text = stringResource(R.string.analyzer_runtime_used, r.runtimeUsed),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.success
                            )
                        }
                        if (r.resolved == 0) {
                            Spacer(Modifier.height(WxSpace.sm))
                            Text(
                                text = stringResource(R.string.analyzer_nothing_resolved),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.warning
                            )
                        }
                    }
                }
            }

            // ── 逐项结论（key 稳定：分析项固定集合，重跑不会错位复用） ──
            items(r.findings, key = { it.key }) { f -> FindingRow(finding = f) }

            // ── 应用 / 导出 ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(WxSpace.sm)) {
                    PrimaryButton(
                        text = stringResource(R.string.analyzer_apply, r.wechatVersion),
                        onClick = ::applyReport,
                        enabled = r.json != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecondaryButton(
                        text = stringResource(R.string.analyzer_copy),
                        onClick = ::copyJson,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.analyzer_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // ── 已缓存产物（可清除） ──
        if (cachedVersions.isNotEmpty()) {
            item {
                WxCard {
                    Column(Modifier.padding(WxSpace.lg)) {
                        SectionTitle(text = stringResource(R.string.analyzer_cached_section))
                        Spacer(Modifier.height(WxSpace.xs))
                        Text(
                            text = stringResource(R.string.analyzer_cached_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        Spacer(Modifier.height(WxSpace.sm))
                        cachedVersions.forEach { v ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = WxSpace.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = v,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary
                                )
                                SecondaryButton(
                                    text = stringResource(R.string.analyzer_clear),
                                    onClick = {
                                        GroupStore.clearAnalyzedSignature(context, v)
                                        cachedVersions = GroupStore.analyzedVersions(context)
                                        pendingMessage = context.getString(R.string.analyzer_cleared, v)
                                    }
                                )
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 单项结论：标签 + 状态 + 取值（多候选时列出候选，方便人工核对） */
@Composable
private fun FindingRow(finding: SignatureAnalyzer.Finding) {
    val colors = LocalAppColors.current
    val (level, levelText, valueText) = when (finding.level) {
        SignatureAnalyzer.Level.OK -> Triple(
            StatusLevel.OK,
            stringResource(R.string.analyzer_level_ok),
            finding.value ?: ""
        )
        SignatureAnalyzer.Level.AMBIGUOUS -> Triple(
            StatusLevel.WARN,
            stringResource(R.string.analyzer_level_ambiguous),
            stringResource(R.string.analyzer_candidates, finding.candidates.size)
        )
        SignatureAnalyzer.Level.MISSING -> Triple(
            StatusLevel.NEUTRAL,
            stringResource(R.string.analyzer_level_missing),
            stringResource(R.string.analyzer_not_found)
        )
    }
    WxCard {
        Column(Modifier.padding(WxSpace.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = finding.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(text = levelText, level = level)
            }
            Spacer(Modifier.height(WxSpace.xs))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            if (finding.candidates.size > 1) {
                Spacer(Modifier.height(WxSpace.xs))
                Text(
                    text = finding.candidates.take(5).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            if (finding.note.isNotEmpty()) {
                Spacer(Modifier.height(WxSpace.xs))
                Text(
                    text = finding.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/** 简单的「标签: 取值」行（分析器页专用，比 MetaRow 更轻，不需要状态标签） */
@Composable
private fun MetaLine(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = WxSpace.xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}
