package cn.dumate.a6studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.ai.LlmProviderId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTrafficPane(state: StudioState, modifier: Modifier = Modifier) {
    var goal by remember { mutableStateOf("提取可执行任务按钮关键词与推荐浏览等待时长") }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. 桌面端本地 Studio MCP 服务端（供 Cursor / Claude / Antigravity 外部接入）
        state.localMcpEndpoint?.let { ep ->
            Surface(
                color = Palette.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Palette.border)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("本地 Studio MCP 服务端（已启用）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.ok)
                        Text(ep, fontSize = 11.sp, color = Palette.textDim)
                    }
                    OutlinedButton(onClick = { state.copyLocalMcpEndpoint() }) {
                        Text("复制地址", fontSize = 11.sp)
                    }
                }
            }
        }

        // 1. ProxyPin 抓包 MCP
        Surface(
            color = Palette.panelAlt,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ProxyPin 抓包 MCP（自动脱敏）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                OutlinedTextField(
                    value = state.proxyPinUrl,
                    onValueChange = { state.proxyPinUrl = it },
                    label = { Text("ProxyPin MCP 地址", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.proxyPinToken,
                    onValueChange = { state.proxyPinToken = it },
                    label = { Text("鉴权 Token（仅内存持有）", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { state.fetchProxyPinTraffic() },
                        enabled = !state.aiBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent)
                    ) {
                        Text("拉取并脱敏流量", fontSize = 12.sp)
                    }
                    state.lastTrafficReport?.let { rep ->
                        Text(
                            "已脱敏：键 ${rep.stats.redactedKeyCount} / 掩码 ${rep.stats.maskedPatternCount}",
                            fontSize = 11.sp,
                            color = Palette.ok,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }

        // 2. 多通道大模型提炼
        Surface(
            color = Palette.panelAlt,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("多通道 LLM 提炼（无 Key 自动降级规则引擎）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LlmProviderId.entries.forEach { pid ->
                        FilterChip(
                            selected = state.llmProvider == pid,
                            onClick = { state.llmProvider = pid },
                            label = { Text(pid.label.substringBefore("（"), fontSize = 11.sp) }
                        )
                    }
                }

                if (state.llmProvider == LlmProviderId.GEMINI || state.llmProvider == LlmProviderId.AUTO) {
                    OutlinedTextField(
                        value = state.llmConfig.geminiApiKey,
                        onValueChange = { state.llmConfig = state.llmConfig.copy(geminiApiKey = it) },
                        label = { Text("Gemini API Key（可选，留空走本地/规则）", fontSize = 11.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (state.llmProvider == LlmProviderId.OPENAI_COMPATIBLE) {
                    OutlinedTextField(
                        value = state.llmConfig.openAiBaseUrl,
                        onValueChange = { state.llmConfig = state.llmConfig.copy(openAiBaseUrl = it) },
                        label = { Text("OpenAI 兼容 Base URL（如 https://api.deepseek.com/v1）", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.llmConfig.openAiApiKey,
                        onValueChange = { state.llmConfig = state.llmConfig.copy(openAiApiKey = it) },
                        label = { Text("API Key（仅内存持有）", fontSize = 11.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("提炼目标", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { state.runAiRefine(goal) },
                        enabled = !state.aiBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent)
                    ) {
                        Text(if (state.aiBusy) "处理中…" else "开始提炼", fontSize = 12.sp)
                    }
                    if (state.lastRefineOutput != null) {
                        OutlinedButton(onClick = { state.applyRefinedToSelectedOrInsert() }) {
                            Text("护栏回填到积木", fontSize = 12.sp)
                        }
                    }
                }

                if (state.aiStatusMsg.isNotBlank()) {
                    Text(state.aiStatusMsg, fontSize = 11.sp, color = Palette.textDim)
                }
            }
        }

        // 3. 提炼结果预览
        state.lastRefineOutput?.let { out ->
            Surface(
                color = Palette.panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Palette.border)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("提炼结果 · ${out.modelUsed}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Text("候选按钮词：${out.refinedKeywords.joinToString("、").ifEmpty { "无" }}", fontSize = 12.sp, color = Palette.info)
                    Text("推荐浏览等待：${out.recommendedBrowseWaitMs} ms", fontSize = 12.sp, color = Palette.text)
                    if (out.extractedApiHints.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        out.extractedApiHints.forEach { hint ->
                            Text("• ${hint.endpoint} [${hint.field}]: ${hint.meaning}", fontSize = 11.sp, color = Palette.textDim)
                        }
                    }
                }
            }
        }
    }
}
