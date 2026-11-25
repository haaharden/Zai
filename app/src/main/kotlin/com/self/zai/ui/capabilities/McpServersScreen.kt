package com.self.zai.ui.capabilities

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.ui.components.zaiSwitchColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpServersScreen(
    servers: List<McpServerConfig>,
    onDiscoverTools: suspend (url: String, authToken: String?) -> Result<List<McpToolSummary>>,
    onSaveServer: (
        existingId: String?,
        name: String,
        url: String,
        authToken: String?,
        tools: List<McpToolSummary>,
        enabled: Boolean,
    ) -> Unit,
    onToggleServer: (serverId: String, enabled: Boolean) -> Unit,
    onDeleteServer: (serverId: String) -> Unit,
    onGetAuthToken: (serverId: String) -> String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var refreshingId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("MCP 工具服务", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editingServer = null
                            showEditor = true
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加 MCP 服务")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ZaiPrimaryButton(
                text = "添加 MCP 服务",
                onClick = {
                    editingServer = null
                    showEditor = true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (servers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "暂无 MCP 服务",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "支持远程 Streamable HTTP 端点，凭证加密保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val enabledToolsCount = servers.filter { it.enabled }.sumOf { it.tools.size }
                ZaiPaperGroup(title = "已配置服务（当前注入 $enabledToolsCount 个工具）") {
                    servers.forEachIndexed { idx, srv ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (srv.enabled && srv.tools.isNotEmpty()) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                        ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = srv.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = srv.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = srv.enabled,
                                    onCheckedChange = { onToggleServer(srv.id, it) },
                                    colors = zaiSwitchColors(),
                                )
                            }

                            if (srv.tools.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    srv.tools.take(12).forEach { tool ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .border(
                                                    0.5.dp,
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(10.dp),
                                                )
                                                .padding(horizontal = 9.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = tool.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (srv.tools.size > 12) {
                                        Text(
                                            text = "+${srv.tools.size - 12}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "尚未同步工具，点击右侧刷新同步",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    enabled = refreshingId != srv.id,
                                    onClick = {
                                        refreshingId = srv.id
                                        scope.launch {
                                            val token = onGetAuthToken(srv.id)
                                            val res = onDiscoverTools(srv.url, token)
                                            refreshingId = null
                                            res.onSuccess { found ->
                                                onSaveServer(srv.id, srv.name, srv.url, null, found, srv.enabled)
                                                Toast.makeText(
                                                    context,
                                                    "已同步 ${found.size} 个 MCP 工具",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }.onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    "同步失败：${err.message}",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "重新同步工具",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingServer = srv
                                        showEditor = true
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "编辑服务",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteServer(srv.id) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除服务",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (idx < servers.lastIndex) {
                            ZaiInsetDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showEditor) {
        McpServerEditorSheet(
            editing = editingServer,
            initialToken = editingServer?.let { onGetAuthToken(it.id) }.orEmpty(),
            onDiscoverTools = onDiscoverTools,
            onDismiss = {
                showEditor = false
                editingServer = null
            },
            onSave = { name, url, token, tools ->
                onSaveServer(
                    editingServer?.id,
                    name,
                    url,
                    token,
                    tools,
                    editingServer?.enabled ?: true,
                )
                showEditor = false
                editingServer = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditorSheet(
    editing: McpServerConfig?,
    initialToken: String,
    onDiscoverTools: suspend (url: String, authToken: String?) -> Result<List<McpToolSummary>>,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, authToken: String, tools: List<McpToolSummary>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember(editing) { mutableStateOf(editing?.url.orEmpty()) }
    var token by remember(editing, initialToken) { mutableStateOf(initialToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val discoveredTools = remember(editing) {
        mutableStateListOf<McpToolSummary>().apply {
            editing?.tools?.let { addAll(it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (editing != null) "编辑 MCP 服务" else "添加 MCP 服务",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ZaiTextField(
                value = name,
                onValueChange = { name = it },
                label = "服务名称",
                placeholder = "例如 Notion / GitHub / 我的搜索",
            )

            ZaiTextField(
                value = url,
                onValueChange = { url = it },
                label = "MCP 端点地址 (Streamable HTTP)",
                placeholder = "https://mcp.example.com/mcp",
                monospace = true,
            )

            ZaiTextField(
                value = token,
                onValueChange = { token = it },
                label = "Bearer Token / Authorization（可选）",
                placeholder = "留空表示免认证，自动加密保存",
                monospace = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "显示或隐藏令牌",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )

            ZaiPrimaryButton(
                text = if (testing) "正在连接并拉取工具…" else "连接并同步工具",
                enabled = !testing && url.isNotBlank(),
                onClick = {
                    testing = true
                    errorMsg = null
                    scope.launch {
                        val res = onDiscoverTools(url, token.ifBlank { null })
                        testing = false
                        res.onSuccess { list ->
                            discoveredTools.clear()
                            discoveredTools.addAll(list)
                            if (list.isEmpty()) {
                                errorMsg = "已连接成功，但该服务未暴露任何工具"
                            }
                        }.onFailure { err ->
                            errorMsg = err.message ?: "连接失败"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (discoveredTools.isNotEmpty()) {
                Text(
                    text = "已发现 ${discoveredTools.size} 个工具",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    discoveredTools.take(20).forEach { tool ->
                        Column {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (tool.description.isNotBlank()) {
                                Text(
                                    text = tool.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZaiSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                ZaiPrimaryButton(
                    text = "保存服务",
                    enabled = url.isNotBlank() && !testing,
                    onClick = { onSave(name, url, token, discoveredTools.toList()) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
