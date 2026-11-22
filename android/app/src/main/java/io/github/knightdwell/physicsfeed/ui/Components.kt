package io.github.knightdwell.physicsfeed.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knightdwell.physicsfeed.data.Paper

@Composable
fun Tag(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperCard(p: Paper, saved: Paper?, dark: Boolean, isNew: Boolean, image: String = "", onOpen: () -> Unit, onStar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val starScale by animateFloatAsState(if (saved != null) 1.25f else 1f, spring(dampingRatio = Spring.DampingRatioHighBouncy), label = "star")
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row {
            if (isNew) Box(Modifier.width(3.dp).padding(vertical = 14.dp).background(cs.primary).size(3.dp, 40.dp))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (bg, fg) = sourceColors(p.source, dark)
                    Tag(p.source + if (p.category.isNotEmpty()) " · ${p.category}" else "", bg, fg)
                    if (p.oa) { Spacer(Modifier.width(4.dp)); Tag("open", if (dark) Color(0xFF1F4A3A) else Color(0xFFCDECCB), if (dark) Color(0xFFA8D5A2) else Color(0xFF1F4D2B)) }
                    Spacer(Modifier.weight(1f))
                    Text(p.date, fontSize = 11.sp, color = cs.outline)
                    IconButton(onClick = onStar, modifier = Modifier.size(32.dp)) {
                        Icon(if (saved != null) Icons.Filled.Star else Icons.Outlined.StarBorder, "Save",
                            tint = if (saved != null) Color(0xFFF6C177) else cs.outline,
                            modifier = Modifier.size(20.dp).graphicsLayer { scaleX = starScale; scaleY = starScale })
                    }
                }
                val own = (saved?.folders?.map { "▤ $it" to true } ?: emptyList()) + (saved?.flairs?.map { "#$it" to false } ?: emptyList())
                if (p.topics.isNotEmpty() || own.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)) {
                        own.forEach { (t, folder) ->
                            if (folder) Tag(t, cs.secondaryContainer, cs.onSecondaryContainer) else Tag(t, cs.tertiaryContainer, cs.onTertiaryContainer)
                        }
                        p.topics.take(5).forEach { Tag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                    }
                }
                Row {
                    Text(tex2text(p.title), fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface,
                        modifier = Modifier.weight(1f))
                    if (image.isNotEmpty()) {
                        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(start = 10.dp).size(76.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh))
                    }
                }
                if (p.authors.isNotEmpty()) {
                    Text(p.authors.joinToString(", ") + if (p.authors.size >= 6) " et al." else "", fontSize = 12.sp, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                if (p.abstract.isNotEmpty()) {
                    Text(tex2text(p.abstract), fontSize = 12.sp, color = cs.outline, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** A compact dropdown: a button showing the current choice, a menu of options. */
@Composable
fun Choice(label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(50)) {
            Text(options.firstOrNull { it.first == selected }?.second ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onSelect(k) }) }
        }
    }
}
