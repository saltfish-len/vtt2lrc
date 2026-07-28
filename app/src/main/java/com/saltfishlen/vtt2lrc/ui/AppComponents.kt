package com.saltfishlen.vtt2lrc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltfishlen.vtt2lrc.ui.theme.LocalLogColors
import com.saltfishlen.vtt2lrc.ui.theme.LogTextStyle

/** 带标题的分区卡片，用于承载一组设置项。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 整行可点的开关项，标题下方可带一行说明。 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 主状态卡：占据视线中心，一眼看出「现在到哪了 / 做完了没有」。
 * 这是给普通用户看的唯一进度来源，详细日志另外折叠。
 */
@Composable
fun StatusCard(
    state: JobState,
    idleTitle: String,
    idleHint: String,
    modifier: Modifier = Modifier
) {
    val logColors = LocalLogColors.current
    val animatedFraction by animateFloatAsState(
        targetValue = state.fraction,
        label = "progress"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            when {
                state.phase == JobPhase.Idle -> {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    StatusTitle(idleTitle)
                    Spacer(Modifier.height(6.dp))
                    StatusHint(idleHint)
                }

                state.phase == JobPhase.Scanning -> {
                    CircularProgressIndicator(modifier = Modifier.size(52.dp), strokeWidth = 5.dp)
                    Spacer(Modifier.height(16.dp))
                    StatusTitle("正在查找文件")
                }

                state.phase == JobPhase.Running -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.size(112.dp),
                            strokeWidth = 10.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            text = "${(animatedFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    StatusTitle("正在处理第 ${(state.done + 1).coerceAtMost(state.total)} 个，共 ${state.total} 个")
                    if (state.currentFile.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        StatusHint(state.currentFile)
                    }
                    if (state.failedCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "已跳过 ${state.failedCount} 个，结束后显示原因",
                            style = MaterialTheme.typography.bodySmall,
                            color = logColors.warn,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 已结束
                state.blocker != null -> {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = logColors.warn,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    StatusTitle("未能开始")
                    Spacer(Modifier.height(6.dp))
                    StatusHint("原因和处理方法见下方")
                }

                state.failedCount == 0 -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = logColors.success,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    StatusTitle("全部完成")
                    Spacer(Modifier.height(6.dp))
                    StatusHint("共处理 ${state.succeeded} 个文件，已保存在原文件夹中")
                }

                else -> {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = logColors.warn,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    StatusTitle(
                        if (state.succeeded == 0) {
                            "${state.failedCount} 个文件未能完成"
                        } else {
                            "已完成 ${state.succeeded} 个，${state.failedCount} 个未成功"
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusHint(
                        if (state.succeeded == 0) {
                            "原因和处理方法见下方"
                        } else {
                            "已完成的文件已保存，未成功的原因见下方"
                        }
                    )
                }
            }

        }
    }
}

@Composable
private fun StatusTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun StatusHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** 每条失败配一张卡：哪里出了问题，以及该怎么办。 */
@Composable
fun GuidanceCard(
    failure: JobFailure,
    modifier: Modifier = Modifier
) {
    // 这里多半是「换个文件夹就好」的小事，用醒目但不吓人的配色
    val accent = LocalLogColors.current.warn

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                if (failure.fileName != null) {
                    Text(
                        text = failure.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = failure.what,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = failure.how,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 详细过程记录：默认折叠，只有需要排查的人才展开。
 * [extraSettings] 放进折叠区，避免技术性开关出现在主界面。
 */
@Composable
fun DetailsSection(
    details: List<String>,
    modifier: Modifier = Modifier,
    extraSettings: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (expanded) "收起详细过程" else "查看详细过程")
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                if (extraSettings != null) {
                    SectionCard(title = "排查选项") { extraSettings() }
                    Spacer(Modifier.height(12.dp))
                }
                LogPanel(details = details, modifier = Modifier.height(220.dp))
            }
        }
    }
}

/** 等宽字体的过程记录，按 ✅/❌ 着色，追加新行时自动滚到底部。 */
@Composable
private fun LogPanel(
    details: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val logColors = LocalLogColors.current
    val successColor = logColors.success
    val warnColor = logColors.warn
    val errorColor = MaterialTheme.colorScheme.error
    val defaultColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(details.size) {
        if (details.isNotEmpty()) {
            listState.animateScrollToItem(details.lastIndex)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "过程记录",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${details.size} 行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (details.isEmpty()) {
            Text(
                text = "暂无记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(details) { line ->
                    Text(
                        text = line,
                        style = LogTextStyle,
                        color = logLineColor(line, successColor, errorColor, warnColor, defaultColor)
                    )
                }
            }
        }
    }
}

private fun logLineColor(
    line: String,
    success: Color,
    error: Color,
    warn: Color,
    default: Color
): Color = when {
    line.startsWith("✅") -> success
    line.startsWith("❌") -> error
    line.startsWith("⚠️") -> warn
    else -> default
}
