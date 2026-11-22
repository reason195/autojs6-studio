package cn.dumate.a6studio.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import cn.dumate.a6studio.catalog.BlockDef
import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.Category
import cn.dumate.a6studio.catalog.Friendly
import cn.dumate.a6studio.ui.Palette as P
import cn.dumate.a6studio.ui.components.EmptyHint
import cn.dumate.a6studio.ui.fields.SearchField

/** The handful of blocks almost every script starts with. */
private val POPULAR = listOf(
    "clickNode", "waitText", "setTextNode", "sleep", "toast", "launchPackage", "pressBack", "swipe"
)

/**
 * Left rail: search, a few one-tap starters, then every block grouped by category.
 *
 * 以前 68 个积木按 10 个分类全部展开，一进界面就是一堵列表墙。现在分类默认收起、顶部先给
 * 8 个「常用」，大多数人不用展开任何分类就能开始写。
 */
@Composable
fun PalettePanel(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
    beginner: Boolean = false,
    onSwitchToAdvanced: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    // 默认全部收起：列表从「一屏 20 项」变成「一屏 10 个分类」。
    val collapsed = remember { mutableStateOf(Catalog.CATS.map { it.id }.toSet()) }

    val grouped = remember(query, beginner) {
        val q = query.trim().lowercase()
        Catalog.CATS.map { cat ->
            val defs = Catalog.byCat(cat.id).filter { d ->
                val textMatch = q.isEmpty() || (d.name + " " + d.type).lowercase().contains(q)
                // Beginner mode: only the curated starters, and never from expert-only groups.
                val beginnerOk = !beginner || (d.type in Friendly.starterBlocks && Friendly.isBeginnerCategory(d.cat))
                textMatch && beginnerOk
            }
            cat to defs
        }.filter { it.second.isNotEmpty() }
    }

    val popular = remember(beginner) {
        POPULAR.mapNotNull { Catalog.byType(it) }.filter { d ->
            !beginner || (d.type in Friendly.starterBlocks && Friendly.isBeginnerCategory(d.cat))
        }
    }

    Column(modifier) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索积木（Ctrl+K）",
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isEmpty() && popular.isNotEmpty()) {
            PopularRow(popular, onAdd)
        }
        if (grouped.isEmpty()) {
            EmptyHint("没有匹配的积木", Modifier.padding(horizontal = 12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            grouped.forEach { (cat, defs) ->
                val isCollapsed = query.isEmpty() && cat.id in collapsed.value
                item(key = "h-${cat.id}") {
                    CategoryHeader(
                        category = cat,
                        beginner = beginner,
                        collapsed = isCollapsed,
                        count = defs.size,
                        onToggle = {
                            collapsed.value = if (isCollapsed) collapsed.value - cat.id else collapsed.value + cat.id
                        }
                    )
                }
                if (!isCollapsed) {
                    items(count = defs.size, key = { i -> "i-${cat.id}-${defs[i].type}" }) { idx ->
                        PaletteItem(defs[idx], cat.color, beginner) { onAdd(defs[idx].type) }
                    }
                }
            }
            if (beginner) {
                item(key = "more") {
                    // 简单模式只显示 10 个起步积木，列表到底会留下大片空白；
                    // 这里给出「剩下的在哪」，而不是让人以为积木只有这些。
                    Text(
                        "想看全部 68 个积木？点这里切换到完整模式",
                        fontSize = 11.sp,
                        color = P.accent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
                            .clickable(onClick = onSwitchToAdvanced)
                    )
                }
            }
        }
    }
}

/**
 * 芯片用短名：`等待控件出现（ms）`、`滑动 () → ()` 这类带参数说明的名字在 60dp 宽的小块里
 * 只会显得吵，点进去之后参数本来就有标签。
 */
private fun chipLabel(def: BlockDef): String =
    Catalog.cleanName(def)
        .substringBefore("（")
        .substringBefore(" (")
        .trim()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopularRow(defs: List<BlockDef>, onAdd: (String) -> Unit) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Text("常用", fontSize = 9.5.sp, color = P.textDim, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            defs.forEach { def ->
                val c = Color(Catalog.colorOf(def.cat))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(P.panelAlt)
                        .border(1.dp, P.border, RoundedCornerShape(7.dp))
                        .clickable { onAdd(def.type) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text(chipLabel(def), fontSize = 11.5.sp, color = P.text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, beginner: Boolean, collapsed: Boolean, count: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(Color(category.color)))
        Spacer(Modifier.width(8.dp))
        Text(if (beginner) Friendly.categoryName(category.id) else category.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.text)
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 10.sp, color = P.textDim)
        Spacer(Modifier.weight(1f))
        Text(if (collapsed) "▸" else "▾", fontSize = 10.sp, color = P.textDim)
    }
}

@Composable
private fun PaletteItem(def: BlockDef, color: Long, beginner: Boolean, onClick: () -> Unit) {
    val c = Color(color)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 3.dp, height = 17.dp).clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.85f)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Catalog.cleanName(def),
                fontSize = 12.5.sp,
                color = P.text,
                maxLines = 1
            )
            if (def.slots.isNotEmpty()) {
                Text("容器", fontSize = 9.5.sp, color = c.copy(alpha = 0.9f))
            } else if (beginner) {
                val hint = Friendly.describe(def.type)
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 9.5.sp, color = P.textDim, maxLines = 2, lineHeight = 13.sp)
                }
            }
        }
    }
}
