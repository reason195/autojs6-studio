package cn.dumate.a6studio.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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

/**
 * Left rail: every block grouped by category. Clicking appends at the smart insertion point
 * (inside a selected container, after a selected block, else the end of the scope).
 */
@Composable
fun PalettePanel(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
    beginner: Boolean = false
) {
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateOf(setOf<String>()) }

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

    Column(modifier) {
        Box(Modifier.padding(10.dp)) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索积木（Ctrl+K）",
                modifier = Modifier.fillMaxWidth()
            )
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
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, beginner: Boolean, collapsed: Boolean, count: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(Color(category.color)))
        Spacer(Modifier.width(7.dp))
        Text(if (beginner) Friendly.categoryName(category.id) else category.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = P.text)
        Spacer(Modifier.width(5.dp))
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.85f)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                Catalog.cleanName(def),
                fontSize = 12.sp,
                color = P.text,
                maxLines = 1
            )
            if (def.slots.isNotEmpty() && !beginner) {
                Text("容器", fontSize = 9.5.sp, color = c.copy(alpha = 0.9f))
            }
            if (beginner) {
                val hint = Friendly.describe(def.type)
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 9.5.sp, color = P.textDim, maxLines = 2, lineHeight = 13.sp)
                }
            }
        }
    }
}