package io.github.knightdwell.physicsfeed.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.knightdwell.physicsfeed.data.Paper
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Papers or news as a stack of cards: swipe left for the next one, right to go back.
 *  Springy, but cheap (graphicsLayer only). The cards are a compact size, centred, not full screen. */
@Composable
fun CardStack(vm: AppViewModel, items: List<Paper>, onOpen: (Paper) -> Unit, onPdf: (Paper) -> Unit) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val imgs by vm.images.collectAsState()
    var index by rememberSaveable(items.firstOrNull()?.id) { mutableIntStateOf(0) }   // stays put when older papers are appended
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) return

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp)) {
        val w = constraints.maxWidth.toFloat()
        val cardH = (maxHeight * 0.62f).coerceAtMost(460.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.3f))
            Box(Modifier.height(cardH).fillMaxWidth()) {
                // up to three cards: the ones underneath peek out, smaller and lower
                for (depth in 2 downTo 0) {
                    val i = index + depth
                    if (i >= items.size) continue
                    val p = items[i]
                    LaunchedEffect(p.id) { vm.wantImage(p) }
                    val progress = (abs(drag.value) / (w * 0.6f)).coerceIn(0f, 1f)
                    val d = if (depth == 0) 0f else depth - progress   // the next card grows as you drag
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (depth == 0) {
                                translationX = drag.value
                                rotationZ = drag.value / w * 10f
                            } else {
                                val s = 1f - 0.05f * d
                                scaleX = s; scaleY = s
                                translationY = 26.dp.toPx() * d
                                alpha = 1f - 0.25f * d
                            }
                        }.then(if (depth == 0) Modifier.pointerInput(index) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        when {
                                            drag.value < -w * 0.28f && index < items.size - 1 -> {
                                                drag.animateTo(-w * 1.3f, tween(220)); index++; drag.snapTo(0f)
                                            }
                                            drag.value > w * 0.28f && index > 0 -> {
                                                drag.animateTo(w * 1.3f, tween(220)); index--; drag.snapTo(0f)
                                            }
                                            else -> drag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        }
                                    }
                                },
                            ) { change, dx -> change.consume(); scope.launch { drag.snapTo(drag.value + dx) } }
                        } else Modifier),
                    ) {
                        val img = p.image.ifEmpty { imgs[p.url] ?: "" }
                        if (p.kind == "news") NewsCard(p, image = img, onOpen = { onOpen(p) })
                        else PaperStackCard(p, image = img, saved = state.saved[p.id], onOpen = { onOpen(p) })
                    }
                }
            }
            Spacer(Modifier.height(40.dp))   // room for the cards peeking out underneath
            val cur = items.getOrNull(index)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { if (index > 0) scope.launch { drag.animateTo(w, tween(180)); index--; drag.snapTo(0f) } }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous")
                }
                Text("${index + 1} / ${items.size}", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                if (cur != null) {
                    FilledTonalIconButton(onClick = { vm.toggleSaved(cur) }) {
                        Icon(if (state.saved.containsKey(cur.id)) Icons.Filled.Star else Icons.Outlined.StarBorder, "Save",
                            tint = if (state.saved.containsKey(cur.id)) Color(0xFFF6C177) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = { sharePaper(ctx, cur) }) { Icon(Icons.Filled.Share, "Share") }
                    if (cur.kind != "news" && (cur.pdf.isNotEmpty() || cur.oa)) {
                        FilledTonalIconButton(onClick = { onPdf(cur) }) { Icon(Icons.Filled.PictureAsPdf, "Read the PDF") }
                    }
                    FilledTonalIconButton(onClick = { if (cur.kind == "news") openUrl(ctx, cur.url) else onOpen(cur) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, if (cur.kind == "news") "Read the article" else "Abstract and more")
                    }
                }
            }
            if (index >= items.size - 1 && cur?.kind != "news") OlderButton(vm)   // last card: offer older papers
            else Text("swipe ← for the next one, → to go back · tap a card to open it", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(0.7f))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaperStackCard(p: Paper, image: String, saved: Paper?, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.red < 0.5f
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, tonalElevation = 2.dp,
        shadowElevation = 6.dp, modifier = Modifier.fillMaxSize()) {
        Column {
            if (image.isNotEmpty()) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(120.dp))
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (bg, fg) = sourceColors(p.source, dark)
                    Tag(p.source + if (p.category.isNotEmpty()) " · ${p.category}" else "", bg, fg)
                    if (p.oa) { Spacer(Modifier.size(4.dp)); Tag("open", cs.secondaryContainer, cs.onSecondaryContainer) }
                    Spacer(Modifier.weight(1f))
                    Text(p.date, fontSize = 11.sp, color = cs.outline)
                }
                val own = (saved?.folders?.map { "▤ $it" } ?: emptyList()) + (saved?.flairs?.map { "#$it" } ?: emptyList())
                if (p.topics.isNotEmpty() || own.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 8.dp)) {
                    own.forEach { Tag(it, cs.tertiaryContainer, cs.onTertiaryContainer) }
                    p.topics.take(3).forEach { Tag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                }
                Text(tex2text(p.title), fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, color = cs.onSurface,
                    maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (p.authors.isNotEmpty()) Text(p.authors.joinToString(", ") + if (p.authors.size >= 6) " et al." else "",
                    fontSize = 13.sp, color = cs.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (p.abstract.isNotEmpty()) Text(tex2text(p.abstract), fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun NewsCard(p: Paper, image: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, tonalElevation = 2.dp,
        shadowElevation = 6.dp, modifier = Modifier.fillMaxSize()) {
        Column {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (image.isNotEmpty()) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainerHigh))),
                        contentAlignment = Alignment.Center) {
                        Text(p.source.take(1), fontSize = 96.sp, fontWeight = FontWeight.Black, color = cs.primary.copy(alpha = 0.35f))
                    }
                }
                Box(Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, cs.surfaceContainer))))
                Text(p.group.ifEmpty { "News" }, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer,
                    modifier = Modifier.padding(14.dp).clip(RoundedCornerShape(50)).background(cs.primaryContainer.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("${p.source} · ${p.date}", fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
                Text(tex2text(p.title), fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, color = cs.onSurface,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (p.abstract.isNotEmpty()) Text(p.abstract, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Grey placeholder cards with a soft shimmer while the feed loads. */
@Composable
fun Skeleton() {
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) { shimmer.animateTo(1f, tween(1100)); shimmer.snapTo(0f) }
    }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        repeat(5) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(118.dp).clip(RoundedCornerShape(16.dp))
                .graphicsLayer { alpha = 0.55f + 0.35f * kotlin.math.sin(shimmer.value * Math.PI).toFloat() }
                .background(cs.surfaceContainerHigh)) {
                Column(Modifier.padding(14.dp)) {
                    Box(Modifier.size(90.dp, 12.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest))
                }
            }
        }
    }
}
