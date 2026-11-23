package cn.dumate.a6studio.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.DisposableEffect
import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.ParamDef
import cn.dumate.a6studio.catalog.ParamKind
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Scope
import cn.dumate.a6studio.picker.PickKind
import cn.dumate.a6studio.store.ProjectRepo
import cn.dumate.a6studio.ui.EmptyCanvasGuide
import cn.dumate.a6studio.ui.Palette as P
import cn.dumate.a6studio.ui.components.EmptyHint
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.currentStudioStyle

/** Blocks that can have a param picked from the device, and which kind of pick it is. */
private fun pickKindFor(block: Block, param: ParamDef): PickKind? {
    if (param.key == "value" && block.type in setOf("clickNode", "longClickNode", "setTextNode", "nodeTextVar", "existsVar", "scrollNode")) {
        return PickKind.SELECTOR
    }
    if (block.type == "coordClick" && (param.key == "x" || param.key == "y")) return PickKind.COORD
    if (block.type == "swipe" && param.key in setOf("x1", "y1", "x2", "y2")) return PickKind.SWIPE
    return null
}

@Composable
fun CanvasPanel(
    repo: ProjectRepo,
    project: Project,
    selectedId: String?,
    onAdd: (String) -> Unit,
    onPick: (String, String, PickKind) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    beginner: Boolean = false,
    onTemplates: () -> Unit = {},
    onShowPalette: () -> Unit = {},
    onShowGuide: () -> Unit = {},
    drag: PaletteDragState? = null
) {
    val scope = repo.currentScope
    val module = repo.currentModule()
    val body = when (scope) {
        is Scope.Module -> module?.children?.get("body").orEmpty()
        Scope.Root -> project.root.children["body"].orEmpty()
    }

    val style = currentStudioStyle
    val dotModifier = if (style.canvasDots) {
        val dotColor = style.canvasDotsColor
        Modifier.drawBehind {
            val step = 16.dp.toPx()
            val radius = 1.dp.toPx()
            var x = step
            while (x < size.width) {
                var y = step
                while (y < size.height) {
                    drawCircle(color = dotColor, radius = radius, center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
    } else Modifier

    val rootId = repo.scopeRootId()
    if (drag != null) {
        DisposableEffect(drag, rootId) { onDispose { drag.unregisterContainer(rootId, "body") } }
    }
    val target = drag?.target
    LazyColumn(
        modifier
            .then(if (drag != null) Modifier.onGloballyPositioned {
                drag.registerContainer(DropContainer(rootId, "body", body.size, it.boundsInRoot()))
            } else Modifier)
            .then(if (drag?.active == true && target?.parentId == rootId) Modifier.border(1.5.dp, P.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp).then(dotModifier)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (module != null) {
                    Text("模块", fontSize = 11.sp, color = P.textDim)
                    Spacer(Modifier.width(8.dp))
                    InlineField(
                        value = module.name,
                        onValueChange = { repo.updateModule(module.id, name = it); onCommit() },
                        placeholder = "模块名",
                        modifier = Modifier.width(140.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("参数", fontSize = 11.sp, color = P.textDim)
                    Spacer(Modifier.width(6.dp))
                    InlineField(
                        value = module.args,
                        onValueChange = { repo.updateModule(module.id, args = it); onCommit() },
                        placeholder = "如 pkg",
                        modifier = Modifier.width(110.dp)
                    )
                } else {
                    Text("主程序", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    Spacer(Modifier.width(7.dp))
                    Text("从上到下执行", fontSize = 11.sp, color = P.textDim)
                }
            }
        }
        if (body.isEmpty()) {
            item {
                if (beginner) {
                    // Three concrete first steps instead of a blank screen with one hint.
                    EmptyCanvasGuide(
                        onTemplates = onTemplates,
                        onShowPalette = onShowPalette,
                        onShowGuide = onShowGuide,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    EmptyHint("把左侧积木点进来，或点「示例」载入京东领券中心流程", Modifier.padding(10.dp))
                }
            }
        }
        items(count = body.size) { i ->
            DropLine(drag, rootId, "body", i)
            BlockCard(
                block = body[i],
                repo = repo,
                project = project,
                selectedId = selectedId,
                onPick = onPick,
                onCommit = onCommit,
                beginner = beginner,
                drag = drag,
                parentId = rootId,
                slot = "body",
                index = i
            )
            Spacer(Modifier.height(10.dp))
        }
        item { DropLine(drag, rootId, "body", body.size) }
    }
}

/** Thin accent bar shown where a dragged palette block would land. */
@Composable
private fun DropLine(drag: PaletteDragState?, parentId: String, slot: String, index: Int) {
    val t = drag?.target ?: return
    if (!drag.active || t.parentId != parentId || t.slot != slot || t.index != index) return
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).height(3.dp)
            .clip(RoundedCornerShape(2.dp)).background(P.accent)
    )
}

@Composable
private fun BlockCard(
    block: Block,
    repo: ProjectRepo,
    project: Project,
    selectedId: String?,
    onPick: (String, String, PickKind) -> Unit,
    onCommit: () -> Unit,
    beginner: Boolean = false,
    drag: PaletteDragState? = null,
    parentId: String = "root",
    slot: String = "body",
    index: Int = 0
) {
    if (drag != null) {
        DisposableEffect(drag, block.id) { onDispose { drag.unregisterCard(block.id) } }
    }
    val def = Catalog.byType(block.type) ?: run {
        Text("未知积木：${block.type}", color = P.err, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        return
    }
    val accent = Color(Catalog.colorOf(def.cat))
    val selected = selectedId == block.id
    val bg = if (selected) accent.copy(alpha = 0.10f) else P.panelAlt

    Column(
        Modifier.fillMaxWidth()
            .then(if (drag != null) Modifier.onGloballyPositioned {
                drag.registerCard(DropCard(block.id, parentId, slot, index, it.boundsInRoot()))
            } else Modifier)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else P.border,
                shape = RoundedCornerShape(7.dp)
            )
            .clickable { repo.select(block.id) }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 15.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(7.dp))
            Text(Catalog.filledName(def, block.params), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = P.text)
            if (block.params["expr"]?.asText()?.isNotBlank() == true) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "fx", fontSize = 9.5.sp, color = P.accent,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .background(P.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // Long-press anywhere on the card then drag up/down to reorder. Keyboard/buttons
            // remain for precision; this is the touch-friendly path on phones.
            Box(
                Modifier.size(16.dp).pointerInput(block.id) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.y > 8f) { repo.shiftBlock(block.id, 1); onCommit() }
                            else if (dragAmount.y < -8f) { repo.shiftBlock(block.id, -1); onCommit() }
                        }
                    )
                },
                contentAlignment = Alignment.Center
            ) { Text("⠿", fontSize = 11.sp, color = P.textDim.copy(alpha = 0.7f)) }
            Spacer(Modifier.width(2.dp))
            BlockMenu(block, repo, onCommit)
        }

        if (beginner) {
            val hint = cn.dumate.a6studio.catalog.Friendly.describe(block.type)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    fontSize = 10.sp,
                    color = P.textDim,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(start = 10.dp, top = 3.dp)
                )
            }
        }

        if (def.params.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ParamRow(block, def.params, repo, onPick, onCommit, beginner = beginner)
        }

        def.slots.forEach { slotDef ->
            val kids = block.children[slotDef.key].orEmpty()
            if (drag != null) {
                DisposableEffect(drag, block.id, slotDef.key) {
                    onDispose { drag.unregisterContainer(block.id, slotDef.key) }
                }
            }
            val hot = drag?.active == true && drag.target?.parentId == block.id && drag.target?.slot == slotDef.key
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 9.dp)
                    .then(if (drag != null) Modifier.onGloballyPositioned {
                        drag.registerContainer(DropContainer(block.id, slotDef.key, kids.size, it.boundsInRoot()))
                    } else Modifier)
                    .border(if (hot) 1.5.dp else 1.dp, if (hot) P.accent else P.borderSoft, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text("↳ ${slotDef.label}", fontSize = 10.sp, color = P.textDim)
                if (kids.isEmpty() && !hot) {
                    Text("（空）", fontSize = 10.sp, color = P.textDim.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp, top = 3.dp))
                }
                kids.forEachIndexed { k, kid ->
                    DropLine(drag, block.id, slotDef.key, k)
                    BlockCard(kid, repo, project, selectedId, onPick, onCommit, beginner, drag, block.id, slotDef.key, k)
                    Spacer(Modifier.height(8.dp))
                }
                DropLine(drag, block.id, slotDef.key, kids.size)
            }
        }
    }
}

/**
 * 卡片右侧直接给出 ↑ ↓ ⧉ ✕，一次点击就能移动、复制、删除。曾经收进 ⋯ 菜单，但删除要点两下、
 * 还得在弹出菜单里找，用户反馈不如直接摆出来。图标做成无边框的淡色小按钮，悬停/按下才显出底色，
 * 删除用错误色，避免四个按钮同样醒目。
 */
@Composable
private fun BlockMenu(block: Block, repo: ProjectRepo, onCommit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardAction("↑") { repo.shiftBlock(block.id, -1); onCommit() }
        CardAction("↓") { repo.shiftBlock(block.id, 1); onCommit() }
        CardAction("⧉") { repo.duplicateBlock(block.id); onCommit() }
        CardAction("✕", danger = true) { repo.removeBlock(block.id); onCommit() }
    }
}

/** One small borderless action on a block card (about 28dp, enough to tap on a phone). */
@Composable
private fun CardAction(glyph: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 13.sp, color = if (danger) P.err.copy(alpha = 0.85f) else P.textDim)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ParamRow(
    block: Block,
    params: List<ParamDef>,
    repo: ProjectRepo,
    onPick: (String, String, PickKind) -> Unit,
    onCommit: () -> Unit,
    beginner: Boolean = false
) {
    // FlowRow: on a phone three params were squeezed onto one line and the labels
    // collapsed into vertical text. Wrapping keeps each label next to its field.
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        params.forEach { param ->
            val value = block.params[param.key]?.takeUnless { it.isBlank() } ?: param.default
            if (param.kind == ParamKind.CODE) {
                // Raw code (imported script chunks, shell commands, JSON payloads) is multi-line by
                // nature. A single-line field showed only the first line and could not accept
                // Enter, so an imported script was effectively read-only. Give it its own row.
                CodeParamField(block, param, value, repo, onCommit)
                return@forEach
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (param.kind == ParamKind.CHECKBOX) {
                    CheckChip(param.label, value.asBool()) {
                        repo.updateParam(block.id, param.key, ParamValue.of(it))
                        onCommit()
                    }
                } else {
                    Text(
                        if (beginner) {
                            cn.dumate.a6studio.catalog.Friendly.paramHint(block.type, param.key)
                                .ifEmpty { param.label } + " "
                        } else "${param.label} ",
                        fontSize = 10.sp, color = P.textDim, maxLines = 1, softWrap = false
                    )
                    when (param.kind) {
                        ParamKind.SELECT -> SelectField(block, param, value, repo, onCommit)
                        ParamKind.NUMBER -> NumberField(block, param, value, repo, onCommit)
                        ParamKind.EXPR -> InlineField(
                            value = value.asText(),
                            onValueChange = { repo.updateParam(block.id, param.key, ParamValue.Text(it)); onCommit() },
                            placeholder = param.label,
                            mono = true,
                            modifier = Modifier.widthIn(min = 90.dp, max = 260.dp),
                            onEditingFinished = { repo.endParamEdit() }
                        )
                        else -> InlineField(
                            value = value.asText(),
                            onValueChange = { repo.updateParam(block.id, param.key, ParamValue.Text(it)); onCommit() },
                            placeholder = param.label,
                            modifier = Modifier.widthIn(min = 70.dp, max = 200.dp),
                            onEditingFinished = { repo.endParamEdit() }
                        )
                    }
                    val pick = pickKindFor(block, param)
                    if (pick != null) {
                        Text(
                            "⌖",
                            fontSize = 12.sp,
                            color = P.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onPick(block.id, param.key, pick) }
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Number input that keeps what the user is typing. Writing `toDoubleOrNull() ?: 0.0` back on
 * every keystroke turned "1." into "1", "-" into "0" and "1e" into "0", so decimals and
 * negatives could not be typed. The text lives locally; the model is updated only with a
 * finite number, and an empty or half-typed value leaves the previous number in place.
 */
@Composable
private fun NumberField(block: Block, param: ParamDef, value: ParamValue, repo: ProjectRepo, onCommit: () -> Unit) {
    var text by remember(block.id, param.key) { mutableStateOf(value.asText()) }
    // Follow outside changes (undo, picker, import) unless they match what is being typed.
    val modelText = value.asText()
    if (text.toDoubleOrNull() != value.asNumber(Double.NaN) && text.toDoubleOrNull() != null) text = modelText
    InlineField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' || it == '-' }
            text = cleaned
            val n = cleaned.toDoubleOrNull()
            if (n != null && n.isFinite()) {
                repo.updateParam(block.id, param.key, ParamValue.Num(n))
                onCommit()
            }
        },
        placeholder = param.label,
        mono = true,
        modifier = Modifier.width(72.dp),
        onEditingFinished = { repo.endParamEdit() }
    )
}

@Composable
private fun CodeParamField(
    block: Block,
    param: ParamDef,
    value: ParamValue,
    repo: ProjectRepo,
    onCommit: () -> Unit
) {
    val lines = value.asText().lines().size
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, fontSize = 10.sp, color = P.textDim, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
            Text("$lines 行 · 可换行编辑", fontSize = 9.sp, color = P.textDim.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(2.dp))
        InlineField(
            value = value.asText(),
            onValueChange = { repo.updateParam(block.id, param.key, ParamValue.Text(it)); onCommit() },
            placeholder = param.label,
            mono = true,
            singleLine = false,
            minLines = if (lines > 3) 3 else lines.coerceAtLeast(1),
            // Imported scripts are often 50+ lines; 14 visible lines made them hard to edit.
            // Beyond this the field scrolls inside itself.
            maxLines = 40,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SelectField(block: Block, param: ParamDef, value: ParamValue, repo: ProjectRepo, onCommit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (block.type == "moduleCall" && param.key == "module") {
        listOf(cn.dumate.a6studio.catalog.SelectOption("", "选择模块")) +
            repo.current.modules.map { cn.dumate.a6studio.catalog.SelectOption(it.id, it.name) }
    } else param.options

    val currentLabel = options.firstOrNull { it.value == value.asText() }?.label ?: value.asText()

    Box {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp))
                .background(P.codeBg)
                .border(1.dp, P.border, RoundedCornerShape(5.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(currentLabel, fontSize = 11.sp, color = P.text, maxLines = 1)
            Text(" ▾", fontSize = 9.sp, color = P.textDim)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, fontSize = 11.5.sp) },
                    onClick = {
                        expanded = false
                        repo.snapshot("修改参数")
                        repo.updateParam(block.id, param.key, ParamValue.Text(opt.value))
                        onCommit()
                    }
                )
            }
        }
    }
}

@Composable
private fun CheckChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (checked) P.accent.copy(alpha = 0.16f) else P.codeBg)
            .border(1.dp, if (checked) P.accent.copy(alpha = 0.5f) else P.border, RoundedCornerShape(4.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (checked) "☑" else "☐", fontSize = 10.sp, color = if (checked) P.accent else P.textDim)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.sp, color = if (checked) P.text else P.textDim)
    }
}