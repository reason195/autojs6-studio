package cn.dumate.a6studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.model.Template
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.BadgeKind
import cn.dumate.a6studio.ui.components.rememberHovered
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
        // No-op consuming scrim: taps on the backdrop must not leak through to the app below.
        Modifier.fillMaxSize().background(Color(0x661F2328)).pointerInput(Unit) { detectTapGestures { } },
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
        // Backdrop taps are swallowed on purpose; use the ✕ (or Esc) to close.
        Modifier.fillMaxSize().background(Color(0x551F2328)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选一个现成的脚本", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Text("选好后可以直接运行；想改成自己的，改积木上的文字就行", fontSize = 11.sp, color = Palette.textDim)
                }
                val (closeHoverMod, closeHovered) = rememberHovered()
                Text("✕", fontSize = 13.sp, color = if (closeHovered) Palette.text else Palette.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .then(closeHoverMod)
                        .background(if (closeHovered) Palette.hover else Color.Transparent)
                        .clickable { state.closeTemplates() }
                        .padding(6.dp))
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
    val (hoverMod, hovered) = rememberHovered()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(hoverMod)
            .background(if (hovered) Palette.hover else Palette.panelAlt)
            .border(1.dp, if (hovered) Palette.accent.copy(alpha = 0.45f) else Palette.borderSoft, RoundedCornerShape(8.dp))
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
    val (hoverMod, hovered) = rememberHovered()
    Row(
        Modifier.widthIn(max = 460.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(hoverMod)
            .background(if (hovered) Palette.hover else Palette.panelAlt)
            .border(1.dp, if (hovered) Palette.accent.copy(alpha = 0.45f) else Palette.borderSoft, RoundedCornerShape(8.dp))
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