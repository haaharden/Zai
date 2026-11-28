package com.self.zai.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.self.zai.BuildConfig
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.update.AppUpdater
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun UpdatesAndKernelSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    val latestRelease = state.latestRelease

    var showRepoEditor by remember { mutableStateOf(false) }
    var repoInput by remember(settings.githubUpdateRepo) { mutableStateOf(settings.githubUpdateRepo) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var updateStatusText by remember(latestRelease) {
        mutableStateOf(
            when {
                latestRelease?.isNewer == true -> "新版本 ${latestRelease.tagName}"
                else -> "v${BuildConfig.VERSION_NAME}"
            },
        )
    }

    ZaiPaperGroup(title = "版本与更新") {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = updateStatusText,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (latestRelease?.isNewer == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = if (state.hotUpdateTag != null) {
                            "Pi ${state.piVersion.ifBlank { "1.6.0" }} · 热更 ${state.hotUpdateTag}"
                        } else {
                            "Pi ${state.piVersion.ifBlank { "1.6.0" }}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = !updateChecking && !updateBusy,
                    onClick = {
                        val slug = repoInput.trim()
                        actions.onUpdateSettings { it.copy(githubUpdateRepo = slug) }
                        updateChecking = true
                        updateStatusText = "检查中…"
                        scope.launch {
                            val res = AppUpdater.checkLatestRelease(slug)
                            updateChecking = false
                            res.onSuccess { info ->
                                actions.onReleaseChecked(info)
                                updateStatusText = if (info.isNewer) {
                                    "新版本 ${info.tagName}"
                                } else {
                                    "已是最新 v${BuildConfig.VERSION_NAME}"
                                }
                            }.onFailure { err ->
                                updateStatusText = err.message ?: "检查失败"
                            }
                        }
                    },
                ) {
                    Text(if (updateChecking) "检查中…" else "检查更新", fontWeight = FontWeight.Medium)
                }
            }

            if (updateBusy) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { updateProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        text = "${(updateProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val rel = latestRelease
            if (rel != null && rel.apkDownloadUrl.isNotBlank()) {
                val sizeMb = if (rel.apkSizeBytes > 0) {
                    String.format("%.1f MB", rel.apkSizeBytes / 1048576.0)
                } else {
                    ""
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZaiPrimaryButton(
                        text = if (rel.isNewer) "更新 ${rel.tagName} $sizeMb".trim()
                        else "重装 ${rel.tagName} $sizeMb".trim(),
                        enabled = !updateBusy && !updateChecking,
                        onClick = {
                            updateBusy = true
                            updateProgress = 0f
                            scope.launch {
                                val dl = AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                    updateProgress = p
                                }
                                updateBusy = false
                                dl.onSuccess { file ->
                                    AppUpdater.installApk(context, file).onSuccess { launched ->
                                        if (!launched) {
                                            Toast.makeText(context, "请允许安装未知应用后重试", Toast.LENGTH_LONG).show()
                                        }
                                    }.onFailure { err ->
                                        Toast.makeText(context, "安装失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }.onFailure { err ->
                                    updateStatusText = err.message ?: "下载失败"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    if (state.shizukuStatus.isReady) {
                        ZaiSecondaryButton(
                            text = "Shizuku",
                            enabled = !updateBusy && !updateChecking,
                            onClick = {
                                updateBusy = true
                                updateProgress = 0f
                                scope.launch {
                                    val dl = AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                        updateProgress = p
                                    }
                                    dl.onSuccess { file ->
                                        val inst = actions.onShizukuInstallApk(file)
                                        updateBusy = false
                                        inst.onSuccess {
                                            Toast.makeText(context, "安装完成", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            updateStatusText = "安装失败：${err.message}"
                                        }
                                    }.onFailure { err ->
                                        updateBusy = false
                                        updateStatusText = err.message ?: "下载失败"
                                    }
                                }
                            },
                        )
                    }
                }

                if (rel.piHotDownloadUrl.isNotBlank() && rel.piHotManifestDownloadUrl.isNotBlank()) {
                    ZaiSecondaryButton(
                        text = "热更新 Pi 内核 (${rel.tagName})",
                        enabled = !updateBusy && !updateChecking,
                        onClick = {
                            updateBusy = true
                            updateProgress = 0f
                            scope.launch {
                                val res = AppUpdater.applyPiHotUpdate(
                                    context = context,
                                    downloadUrl = rel.piHotDownloadUrl,
                                    manifestUrl = rel.piHotManifestDownloadUrl,
                                    tagName = rel.tagName,
                                    onProgress = { p -> updateProgress = p },
                                )
                                updateBusy = false
                                res.onSuccess { tag ->
                                    actions.onReloadPiHotUpdate()
                                    updateStatusText = "内核已热更 $tag（签名已校验）"
                                    Toast.makeText(context, "Pi 热更新已验签并生效", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    updateStatusText = err.message ?: "热更新失败"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showRepoEditor) "收起更新源" else "更新源：${settings.githubUpdateRepo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showRepoEditor = !showRepoEditor }
                        .padding(vertical = 4.dp),
                )
                if (state.hotUpdateTag != null) {
                    Text(
                        text = "重置内核",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                actions.onResetPiHotUpdate()
                                Toast.makeText(context, "已恢复内置内核", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = showRepoEditor) {
                ZaiTextField(
                    value = repoInput,
                    onValueChange = {
                        repoInput = it
                        actions.onUpdateSettings { s -> s.copy(githubUpdateRepo = it.trim()) }
                    },
                    placeholder = "haaharden/Zai",
                    monospace = true,
                )
            }
        }
    }
}
