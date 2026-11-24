package com.self.zai.ui.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.SkillEntry
import com.self.zai.skills.SkillMarkdownParser
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiMultilineField
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.ui.components.zaiSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    skills: List<SkillEntry>,
    onSaveSkill: (existingId: String?, name: String, description: String, instructions: String, enabled: Boolean) -> Unit,
    onToggleSkill: (skillId: String, enabled: Boolean) -> Unit,
    onDeleteSkill: (skillId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var editingSkill by remember { mutableStateOf<SkillEntry?>(null) }
    var prefillParsed by remember { mutableStateOf<SkillMarkdownParser.ParsedSkill?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("Skills 技能", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editingSkill = null
                            prefillParsed = null
                            showEditor = true
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建技能")
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
            ZaiPaperGroup(title = "按需加载技能（兼容 SKILL.md）") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "启用的技能只向模型暴露名称与简介，命中使用场景时自动通过 read_skill 按需加载正文，不浪费上下文；也可在聊天输入 /技能名 直接触发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ZaiPrimaryButton(
                            text = "新建技能",
                            onClick = {
                                editingSkill = null
                                prefillParsed = null
                                showEditor = true
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ZaiSecondaryButton(
                            text = "粘贴 SKILL.md",
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                val parsed = SkillMarkdownParser.parse(clip)
                                if (parsed == null) {
                                    Toast.makeText(context, "剪贴板为空，请先复制 SKILL.md 内容", Toast.LENGTH_SHORT).show()
                                } else {
                                    editingSkill = null
                                    prefillParsed = parsed
                                    showEditor = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (skills.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 18.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "尚未添加任何技能",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "把常用的手机操作流程（如比价下单、日报整理、固定回消息模板）存为 Skill，随时复用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ZaiPaperGroup(title = "已保存技能 (${skills.count { it.enabled }}/${skills.size} 启用)") {
                    skills.forEachIndexed { idx, skill ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "/${skill.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (skill.enabled) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { onToggleSkill(skill.id, it) },
                                    colors = zaiSwitchColors(),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        val md = SkillMarkdownParser.exportMarkdown(skill)
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("SKILL.md", md))
                                        Toast.makeText(context, "已复制为 SKILL.md", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = "复制 SKILL.md",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingSkill = skill
                                        prefillParsed = null
                                        showEditor = true
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "编辑技能",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSkill(skill.id) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除技能",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (idx < skills.lastIndex) {
                            ZaiInsetDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showEditor) {
        SkillEditorSheet(
            editing = editingSkill,
            prefill = prefillParsed,
            onDismiss = {
                showEditor = false
                editingSkill = null
                prefillParsed = null
            },
            onSave = { name, desc, body ->
                onSaveSkill(
                    editingSkill?.id,
                    name,
                    desc,
                    body,
                    editingSkill?.enabled ?: true,
                )
                showEditor = false
                editingSkill = null
                prefillParsed = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditorSheet(
    editing: SkillEntry?,
    prefill: SkillMarkdownParser.ParsedSkill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, instructions: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(editing, prefill) { mutableStateOf(editing?.name ?: prefill?.name.orEmpty()) }
    var description by remember(editing, prefill) {
        mutableStateOf(editing?.description ?: prefill?.description.orEmpty())
    }
    var instructions by remember(editing, prefill) {
        mutableStateOf(editing?.instructions ?: prefill?.instructions.orEmpty())
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
                text = if (editing != null) "编辑技能" else "新建技能",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ZaiTextField(
                value = name,
                onValueChange = { name = it },
                label = "技能名称（支持 /名称 触发）",
                placeholder = "例如 price-compare 或 点外卖",
                monospace = true,
            )

            ZaiTextField(
                value = description,
                onValueChange = { description = it },
                label = "一句话触发描述",
                placeholder = "告诉模型何时需要加载此技能",
            )

            ZaiMultilineField(
                value = instructions,
                onValueChange = { instructions = it },
                label = "技能完整指引（Markdown）",
                placeholder = "写明步骤、判断标准、边界约束与输出格式…",
                minLines = 6,
                maxLines = 14,
            )

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
                    text = "保存技能",
                    enabled = name.isNotBlank() && instructions.isNotBlank(),
                    onClick = { onSave(name, description, instructions) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
