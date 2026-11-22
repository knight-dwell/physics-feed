package io.github.knightdwell.physicsfeed.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knightdwell.physicsfeed.data.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in PDF reader (Android's PdfRenderer): pages render on demand, pinch to zoom, double-tap for 2×. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(file: File, paper: Paper, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val lock = remember { Mutex() }   // PdfRenderer can open one page at a time
    val ratios = remember(renderer) {   // page shapes, read once up front
        renderer?.let { r -> (0 until r.pageCount).map { i -> runCatching { r.openPage(i).use { it.width.toFloat() / it.height } }.getOrDefault(0.707f) } }
            ?: emptyList()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shown by animateFloatAsState(scale, label = "zoom")
    val zoom = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 4f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                androidx.compose.foundation.layout.Column {
                    Text(tex2text(paper.title), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    Text("page $page of ${renderer?.pageCount ?: 0}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { openPdf(ctx, file) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another app") }
                IconButton(onClick = { shareFile(ctx, file, "application/pdf", "Send PDF") }) { Icon(Icons.Filled.Share, "Share PDF") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }) { pad ->
        if (renderer == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Couldn't open this PDF.") }
            return@Scaffold
        }
        BoxWithConstraints(
            Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .transformable(zoom)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1.2f) 1f else 2f; if (scale == 1f) offset = Offset.Zero }) },
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            LazyColumn(
                state = listState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = shown; scaleY = shown; translationX = offset.x; translationY = offset.y
                },
            ) {
                items(renderer.pageCount) { i ->
                    val ratio = ratios.getOrElse(i) { 0.707f }
                    val bmp by produceState<Bitmap?>(null, i, widthPx) {
                        value = withContext(Dispatchers.IO) {
                            lock.withLock {
                                runCatching {
                                    renderer.openPage(i).use { pg ->
                                        val w = (widthPx * 1.6f).toInt().coerceAtMost(2400)   // sharp enough to zoom a bit
                                        val h = (w / ratio).toInt()
                                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                                            b.eraseColor(AColor.WHITE)
                                            pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        }
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(Color.White),
                        contentAlignment = Alignment.Center) {
                        val b = bmp
                        if (b == null) CircularProgressIndicator(Modifier.padding(24.dp))
                        else Image(b.asImageBitmap(), "page ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
