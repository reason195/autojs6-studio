package cn.dumate.a6studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.model.Template
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.BadgeKind
import cn.dumate.a6studio.ui.panes.SmallButton

/**
 * Five short steps for someone who has never scripted anything. Each page answers one question:
 * what is this, what do I click first, how do I see it work, what if it fails, how do I change it.
 */
private data class GuideStep(
    val title: String,
    val body: List<String>,
    val tryLabel: String? = null,
    val tryAction: (() -> Unit)? = null
)

@Composable
fun GuideOverlay(state: StudioState) {
    var step by remember { mutableStateOf(0) }

    val steps = listOf(
        GuideStep(
            title = "这是干什么用的？",
            body = listOf(
                "把「点开某个 App、按一下这里的按钮、填一段文字」这类重复操作，做成一个能自动执行的脚本。",
                "你不用写代码：左边是「积木」，点一下就加到中间，改改上面的字就是你的脚本了。",
                "下面几页教你把第一个脚本跑起来，大约 2 分钟。"
            ),
            tryLabel = "开始（1/5）"
        ),
        GuideStep(
            title = "先看一个能跑的模板",
            body = listOf(
                "「模板」里已经写好了几个能直接用的脚本，比如「打开 App 并点一个按钮」。",
                "选一个最接近你想法的，不用从零开始。"
            ),
            tryLabel = "打开模板库",
            tryAction = {
                state.dismissGuide()
                state.openTemplates()
            }
        ),
        GuideStep(
            title = "在这里点「运行」看效果",
            body = listOf(
                "右边有个「运行」，点它会在内置的模拟手机里跑一遍，不会碰你真实的手机。",
                "跑完能看到手机上出现了什么、每一步做了什么，确认没问题再考虑上真机。"
            ),
            tryLabel = "我知道了"
        ),
        GuideStep(
            title = "想改成你自己的？改文字就行",
            body = listOf(
                "点中间任意一个积木，上面的文字框都能直接改。",
                "比如「点击控件 立即领取」，把「立即领取」改成你屏幕上真实看到的字即可。",
                "不知道那个按钮叫什么？点旁边的 ⌖ 按钮，连着手机时能直接从屏幕上点选。"
            ),
            tryLabel = "我知道了"
        ),
        GuideStep(
            title = "出错了怎么办",
            body = listOf(
                "「诊断」会提前告诉你哪里可能有问题，并直接说人话（比如「这个按钮可能找不到」）。",
                "运行失败时，日志里会写清楚停在哪一步、可能是哪块没点上。",
                "实在不行，回「帮助」页，或者重新选一个模板，改动小一点再试。"
            ),
            tryLabel = "开始用吧"
        )
    )

    val current = steps[step.coerceIn(0, steps.size - 1)]

    Box(
        Modifier.fillMaxSize().background(Color(0x661F2328)).clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${step + 1} 步 / 共 ${steps.size} 步",
                    fontSize = 10.5.sp,
                    color = Palette.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Palette.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "跳过", fontSize = 11.sp, color = Palette.textDim,
                    modifier = Modifier.clickable { state.dismissGuide() }.padding(6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(current.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Spacer(Modifier.height(10.dp))
            current.body.forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("·", fontSize = 13.sp, color = Palette.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(line, fontSize = 12.5.sp, color = Palette.text, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) {
                    SmallButton("上一步") { step -= 1 }
                }
                Spacer(Modifier.weight(1f))
                val advance = {
                    if (step >= steps.size - 1) state.dismissGuide() else step += 1
                }
                if (current.tryAction != null) {
                    SmallButton(current.tryLabel ?: "下一步", primary = true) {
                        current.tryAction.invoke()
                    }
                } else {
                    SmallButton(current.tryLabel ?: "下一步", primary = true) { advance() }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.size(width = 18.dp, height = 3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= step) Palette.accent else Palette.border)
                    )
                }
            }
        }
    }
}

/** Pick a ready-made script; each card states exactly what it does and whether it needs a phone. */
@Composable
fun TemplateOverlay(state: StudioState) {
    Box(
        Modifier.fillMaxSize().background(Color(0x551F2328)).clickable { state.closeTemplates() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                .clickable(enabled = false) { }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选一个现成的脚本", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Text("选好后可以直接运行；想改成自己的，改积木上的文字就行", fontSize = 11.sp, color = Palette.textDim)
                }
                Text("✕", fontSize = 13.sp, color = Palette.textDim, modifier = Modifier.clickable { state.closeTemplates() }.padding(6.dp))
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp)) {
                items(count = Templates.all.size) { i ->
                    val t = Templates.all[i]
                    TemplateCard(t) { state.applyTemplate(t) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text(
                "这些模板都能在内置模拟手机里跑起来，不需要先连真机。",
                fontSize = 10.5.sp, color = Palette.textDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun TemplateCard(template: Template, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.panelAlt)
            .border(1.dp, Palette.borderSoft, RoundedCornerShape(8.dp))
            .clickable(onClick = onPick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(template.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.text)
            Spacer(Modifier.width(8.dp))
            if (template.needsDevice) {
                Badge("需要手机", BadgeKind.WARN)
            } else {
                Badge("可直接运行", BadgeKind.OK)
            }
            Spacer(Modifier.weight(1f))
            Text("用它 →", fontSize = 11.sp, color = Palette.accent)
        }
        Spacer(Modifier.height(5.dp))
        Text(template.summary, fontSize = 11.5.sp, color = Palette.textDim, lineHeight = 17.sp)
    }
}

/**
 * Shown in place of an empty canvas: three concrete next actions, no jargon. Takes callbacks
 * rather than the whole state so it stays usable from any screen.
 */
@Composable
fun EmptyCanvasGuide(
    onTemplates: () -> Unit,
    onShowPalette: () -> Unit,
    onShowGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text("还没有任何积木", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Spacer(Modifier.height(6.dp))
        Text("从这里开始，任选一种：", fontSize = 12.sp, color = Palette.textDim)
        Spacer(Modifier.height(18.dp))

        NextStepCard("① 用现成模板（最快）", "已经写好的脚本，点开就能跑，再按需改文字") {
            onTemplates()
        }
        Spacer(Modifier.height(10.dp))
        NextStepCard("② 点左边的积木", "一个积木 = 一个动作，比如「点击控件」「等待」「滑动」") {
            onShowPalette()
        }
        Spacer(Modifier.height(10.dp))
        NextStepCard("③ 重看 2 分钟上手指南", "五页说明，讲清楚每一步点哪里") {
            onShowGuide()
        }
    }
}

@Composable
private fun NextStepCard(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 460.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.panelAlt)
            .border(1.dp, Palette.borderSoft, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Palette.text)
            Spacer(Modifier.height(3.dp))
            Text(hint, fontSize = 11.sp, color = Palette.textDim, lineHeight = 16.sp)
        }
        Text("›", fontSize = 18.sp, color = Palette.accent)
    }
}

/**
 * 快速开始（一键上手首页）：第一次打开就停在这里，也是顶栏「主页」常驻入口。
 * 视觉按现代引导页/空状态设计：Hero 大标题 + 副标题讲价值，卡片带阴影、圆角、
 * 渐变图标块与主次分明的操作按钮；真机一键生成卡片随连接状态变化。
 */
@Composable
fun QuickStart(state: StudioState) {
    val connected = state.deviceStatusLevel == "ok" || state.deviceInfo != null
    val deviceLabel = state.deviceInfo?.label?.substringBefore('(')?.trim()
        ?: state.deviceStatusText.removePrefix("真机：")

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        // 窄屏手机（横屏 360dp 左右）用紧凑节奏与更小卡内边距，桌面/大屏用宽松节奏。
        val compact = maxWidth < 480.dp
        val topPad = if (compact) 22.dp else 48.dp
        val heroSize = if (compact) 23.sp else 30.sp
        val heroLine = if (compact) 31.sp else 38.sp
        val subSize = if (compact) 12.5.sp else 14.sp
        val iconPad = if (compact) 11.dp else 18.dp
        val iconSize = if (compact) 38.dp else 52.dp
        val iconShape = if (compact) 10.dp else 13.dp
        val hintSize = if (compact) 11.sp else 12.sp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(topPad))

            // ── Hero 区：品牌小标 + 大标题 + 副标题 ──
            Badge("一键上手 · 不用写代码", BadgeKind.OK)
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
            Text(
                "像叠积木一样，做出你的第一个自动化脚本",
                fontSize = heroSize, fontWeight = FontWeight.Bold, color = Palette.text,
                lineHeight = heroLine, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 18.dp else 32.dp)
            )
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            Text(
                "从模板开始、真机一键生成、或自己拼——三条路，选一条就走。",
                fontSize = subSize, color = Palette.textDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 22.dp else 40.dp)
            )
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))

            // 连接状态条：连上给绿勾，没连给提示，但不堵路
            if (connected) {
                Badge("真机已连接 · $deviceLabel", BadgeKind.OK)
            } else {
                Text("还没连真机？这条不着急，方案①先在模拟器里玩", fontSize = if (compact) 11.sp else 12.sp, color = Palette.textDim)
            }

            Spacer(Modifier.height(if (compact) 18.dp else 30.dp))

            // ── 三张大卡片 ──
            Column(Modifier.fillMaxWidth().widthIn(max = 660.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
                QuickStartCard(
                    icon = "计",
                    title = "从现成模板开始",
                    tag = "最快 · 不需要手机",
                    hint = "已经写好的脚本，点开就能在模拟器里运行；先看效果，再按需改文字。",
                    actionLabel = "打开模板库",
                    primary = true,
                    accent = Palette.accent,
                    iconSize = iconSize, iconShape = iconShape,
                    compact = compact, hintSize = hintSize, iconPad = iconPad,
                    onClick = { state.openFromQuickStart { state.openTemplates() } }
                )
                QuickStartCard(
                    icon = "机",
                    title = "真机一键生成",
                    tag = if (connected) "真机已连接 · 直接开始" else "需要连接 AutoJs6",
                    hint = if (connected)
                        "我读你真机当前页 → 自动生成「打开 App → 等按钮 → 点击」的脚本并试跑。"
                    else
                        "连上 AutoJs6 后，我自动采集屏幕：打开 App → 等按钮出现 → 点它 → 生成脚本。",
                    actionLabel = if (connected) "开始一键生成" else "去连接真机",
                    primary = !connected,
                    accent = Palette.info,
                    iconSize = iconSize, iconShape = iconShape,
                    compact = compact, hintSize = hintSize, iconPad = iconPad,
                    onClick = {
                        if (connected) {
                            state.openFromQuickStart { state.autoOneClickStart() }
                        } else {
                            // 直达「一键上手」标签：连不连都直接看到主操作（未连时页内给连接指引），
                            // 手机端顺带切到「面板」分栏，不落在编辑画布上。
                            state.openFromQuickStart { state.openDeviceGuide() }
                        }
                    }
                )
                QuickStartCard(
                    icon = "⌗",
                    title = "从头开始，自己拼",
                    tag = "进阶玩法",
                    hint = "点左侧积木栏里的「点击控件」「等待」「滑动」，每点一个就加到中间画布上。",
                    actionLabel = "打开空画布",
                    primary = false,
                    accent = Palette.textDim,
                    iconSize = iconSize, iconShape = iconShape,
                    compact = compact, hintSize = hintSize, iconPad = iconPad,
                    onClick = { state.openFromQuickStart { /* 留空画布即可，空画布上已有下一步提示 */ } }
                )
            }

            Spacer(Modifier.height(if (compact) 20.dp else 30.dp))

            // ── 次要用例：不堵主路，但要能再看到 ──
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("重看 2 分钟指南", fontSize = if (compact) 11.5.sp else 12.sp, color = Palette.accent,
                    modifier = Modifier.clickable {
                        state.openFromQuickStart { state.showGuide = true }
                    }.padding(8.dp))
                Box(Modifier.width(1.dp).height(14.dp).background(Palette.border))
                Text("先随便逛逛", fontSize = if (compact) 11.5.sp else 12.sp, color = Palette.textDim,
                    modifier = Modifier.clickable { state.dismissGuide() }.padding(8.dp))
            }
            Spacer(Modifier.height(if (compact) 28.dp else 40.dp))
        }
    }
}

/**
 * 一张带层次感的入口卡片：渐变图标块 + 标题/标签/描述 + 主操作按钮。
 * primary=true 时卡片描边 + 按钮用强调色，视觉主次一眼可辨。
 * 窄屏（compact）下图标块变小、卡内边距变薄、描述字号略小，避免换行挤爆：
 * 原桌面版把行内按钮挤到 4~5 行文字塞进窄列，卡片被拉得 400dp+ 高。
 */
@Composable
private fun QuickStartCard(
    icon: String,
    title: String,
    tag: String,
    hint: String,
    actionLabel: String,
    primary: Boolean,
    accent: Color,
    iconSize: Dp,
    iconShape: Dp,
    compact: Boolean,
    hintSize: TextUnit,
    iconPad: Dp,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 11.dp else 14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.panel),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (primary) 6.dp else 2.dp,
            pressedElevation = 1.dp
        ),
        border = if (primary) androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f))
                 else androidx.compose.foundation.BorderStroke(1.dp, Palette.borderSoft)
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(iconPad),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
                // 渐变图标块：主卡片用强调色渐变，次级用灰
                Box(
                    Modifier.size(iconSize).clip(RoundedCornerShape(iconShape))
                        .background(
                            if (primary)
                                Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))
                            else
                                Brush.linearGradient(listOf(Palette.panelAlt, Palette.borderSoft))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        icon,
                        fontSize = if (compact) 16.sp else 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (primary) Color.White else Palette.textDim
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                        Spacer(Modifier.width(8.dp))
                        val tagColor = if (primary) Palette.accent else Palette.textDim
                        Text(
                            tag,
                            fontSize = if (compact) 9.sp else 10.sp,
                            color = tagColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tagColor.copy(alpha = 0.12f))
                                .padding(horizontal = if (compact) 5.dp else 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
                    Text(hint, fontSize = hintSize, color = Palette.textDim, lineHeight = if (compact) 15.sp else 18.sp)
                }
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (primary) Palette.accent else Palette.panelAlt,
                    contentColor = if (primary) Palette.onAccent else Palette.text
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = if (compact) 7.dp else 9.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = if (primary) 3.dp else 0.dp),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}
