package io.github.knightdwell.physicsfeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knightdwell.physicsfeed.data.Fetcher
import io.github.knightdwell.physicsfeed.data.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: AppViewModel, view: View, onView: (View) -> Unit, onOpen: (Paper) -> Unit, onNav: (String) -> Unit) {
    val feed by vm.feed.collectAsState()
    val state by vm.state.collectAsState()
    val config by vm.config.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val dark = isDark(state.theme)
    var menu by remember { mutableStateOf(false) }
    val list = remember(feed, state, config, view) { vm.items(view) }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.width(300.dp)) {
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                var n = 0   // keys must be unique even if a name appears twice
                fun entry(v: View, label: String, count: Int?) {
                    item(key = "${n++}") {
                        NavigationDrawerItem(
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            badge = { if (count != null && count > 0) Text("$count", fontSize = 12.sp) },
                            selected = v == view,
                            onClick = { onView(v); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp).height(44.dp),
                        )
                    }
                }
                fun head(t: String) { item(key = "h${n++}") { Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)) } }
                item { Text("Physics Feed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, bottom = 8.dp)) }
                listOf(View.ForYou, View.Today, View.All, View.Open, View.News).forEach { entry(it, vm.title(it), vm.items(it).size) }
                head("Saved")
                entry(View.Saved, "All saved", state.saved.size)
                state.folders.forEach { entry(View("folder", it), "▤  $it", vm.items(View("folder", it)).size) }
                state.saved.values.flatMap { it.flairs }.distinct().sorted().forEach { entry(View("flair", it), "#$it", vm.items(View("flair", it)).size) }
                config.topics.forEach { (g, tps) ->
                    head(g)
                    entry(View("group", g), "All ${g.lowercase()}", vm.items(View("group", g)).size)
                    tps.keys.forEach { entry(View("topic", it), "·  $it", vm.items(View("topic", it)).size) }
                }
                head("Journals")
                (listOf("arXiv") + config.journals.keys).forEach { entry(View("source", it), it + if (vm.catalog.isOpenAccess(it)) "  🔓" else "", vm.items(View("source", it)).size) }
                if (config.news.isNotEmpty()) {
                    head("News")
                    config.news.values.map { it.second }.distinct().forEach { g ->
                        entry(View("newsgroup", g), g, vm.items(View("newsgroup", g)).size)
                        config.news.filter { it.value.second == g }.keys.forEach { entry(View("source", it), "·  $it", vm.items(View("source", it)).size) }
                    }
                }
            }
        }
    }) {
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.title(view), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${list.size} · updated ${feed.updated.ifEmpty { "never" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                },
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Topics, journals, news") } },
                actions = {
                    IconButton(onClick = { onNav("search") }) { Icon(Icons.Filled.Search, "Search papers") }
                    IconButton(onClick = { vm.refresh() }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "Fetch now") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("My topics") }, onClick = { menu = false; onNav("topics") })
                        DropdownMenuItem(text = { Text("Journals & news") }, onClick = { menu = false; onNav("journals") })
                        DropdownMenuItem(text = { Text("Theme: " + mapOf("system" to "follow phone", "dark" to "dark", "light" to "light")[state.theme]) },
                            onClick = { vm.setTheme(mapOf("system" to "dark", "dark" to "light", "light" to "system")[state.theme] ?: "system") })
                        DropdownMenuItem(text = { Text("Export settings (share with colleagues)") }, onClick = { menu = false; onNav("export") })
                        DropdownMenuItem(text = { Text("Import settings…") }, onClick = { menu = false; onNav("import") })
                        DropdownMenuItem(text = { Text("Manage folders") }, onClick = { menu = false; onNav("folders") })
                        DropdownMenuItem(text = { Text("Updates") }, onClick = { menu = false; onNav("updates") })
                        DropdownMenuItem(text = { Text("Help") }, onClick = { menu = false; onNav("help") })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }) { pad ->
            Column(Modifier.padding(pad)) {
                val quick = listOf(View.ForYou, View.Today, View.Saved, View.Open, View.News) +
                    config.quick.filter { it == "arXiv" || it in config.journals }.map { View("source", it) }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(quick) { v ->
                        FilterChip(selected = v == view, onClick = { onView(v) },
                            label = { Text(if (v.kind == "source") (if (v.key == "Rev. Mod. Phys.") "RMP" else v.key) else vm.title(v)) })
                    }
                }
                if (feed.errors.isNotEmpty()) Text("couldn't reach: " + feed.errors.keys.joinToString(", "), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    if (list.isEmpty()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)) {
                            Text(
                                when {
                                    feed.items.isEmpty() && refreshing -> "Fetching your first papers… about a minute."
                                    feed.items.isEmpty() -> "No papers yet. Pull down to fetch."
                                    view == View.Saved -> "Tap ☆ on a paper to keep it here."
                                    else -> "Nothing here yet."
                                }, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(list, key = { it.id }) { p ->
                                PaperCard(p, state.saved[p.id], dark, isNew = state.lastOpen.isNotEmpty() && p.firstSeen > state.lastOpen.take(10),
                                    onOpen = { onOpen(p) }, onStar = { vm.toggleSaved(p) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: AppViewModel, paper: Paper, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    var abstract by remember(paper.id) { mutableStateOf(paper.abstract) }
    var busy by remember { mutableStateOf<String?>(null) }
    var citeMenu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val dark = isDark(state.theme)
    LaunchedEffect(paper.id) { vm.loadFullAbstract(paper) { abstract = it } }

    fun readPdf() {
        busy = "Getting the PDF…"
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
                    val file = File(dir, paper.id.replace(Regex("[^A-Za-z0-9.]+"), "_") + ".pdf")
                    if (!file.exists()) {
                        val url = Fetcher.pdfLink(paper).ifEmpty { throw RuntimeException("no PDF link") }
                        val bytes = Fetcher.getBytes(url)
                        if (bytes.size < 5 || String(bytes, 0, 4) != "%PDF") throw RuntimeException("not a PDF")
                        file.writeBytes(bytes)
                    }
                    file
                }.getOrNull()
            }
            busy = null
            if (f != null) openPdf(ctx, f) else openUrl(ctx, paper.url)   // no PDF: the article page instead
        }
    }

    fun cite(fmt: String, how: String) {
        busy = "Getting the citation…"
        scope.launch {
            val txt = withContext(Dispatchers.IO) { runCatching { Fetcher.citation(paper, fmt) }.getOrNull() }
            busy = null
            if (txt == null) { vm.toast("Couldn't get the citation"); return@launch }
            if (how == "copy") copy(ctx, if (fmt == "bibtex") "BibTeX" else "RIS", txt)
            else {
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val first = paper.authors.firstOrNull()?.split(" ")?.last() ?: "paper"
                val f = File(dir, "${first}_${paper.date.take(4)}" + if (fmt == "bibtex") ".bib" else ".ris").apply { writeText(txt + "\n") }
                shareFile(ctx, f, if (fmt == "bibtex") "application/x-bibtex" else "application/x-research-info-systems", "Send citation")
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(paper.source, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { vm.toggleSaved(paper) }) {
                    Icon(if (state.saved.containsKey(paper.id)) Icons.Filled.Star else Icons.Outlined.BookmarkBorder, "Save",
                        tint = if (state.saved.containsKey(paper.id)) androidx.compose.ui.graphics.Color(0xFFF6C177) else cs.onSurfaceVariant)
                }
                IconButton(onClick = { sharePaper(ctx, paper) }) { Icon(Icons.Filled.Share, "Share") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface))
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
            SelectionContainer { Text(tex2text(paper.title), style = MaterialTheme.typography.headlineSmall, color = cs.primary, fontWeight = FontWeight.Bold) }
            if (paper.authors.isNotEmpty()) Text(paper.authors.joinToString(", ") + if (paper.authors.size >= 6) " et al." else "",
                color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                val (bg, fg) = sourceColors(paper.source, dark)
                Tag(paper.source + (if (paper.category.isNotEmpty()) " · ${paper.category}" else "") + " · ${paper.date}", bg, fg)
                if (paper.oa) Tag("open access", cs.secondaryContainer, cs.onSecondaryContainer)
                paper.topics.forEach { Tag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                state.saved[paper.id]?.let { s ->
                    s.folders.forEach { Tag("▤ $it", cs.secondaryContainer, cs.onSecondaryContainer) }
                    s.flairs.forEach { Tag("#$it", cs.tertiaryContainer, cs.onTertiaryContainer) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paper.kind != "news" && (paper.pdf.isNotEmpty() || paper.oa)) {
                    FilledTonalButton(onClick = { readPdf() }) { Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (paper.pdf.isNotEmpty()) "Read PDF" else "Read (open access)") }
                }
                OutlinedButton(onClick = { openUrl(ctx, paper.url) }) { Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (paper.kind == "news") "Article" else "Journal page") }
                if (paper.kind != "news") Row {
                    OutlinedButton(onClick = { citeMenu = true }) { Icon(Icons.Filled.FormatQuote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cite") }
                    DropdownMenu(expanded = citeMenu, onDismissRequest = { citeMenu = false }) {
                        DropdownMenuItem(text = { Text("Copy BibTeX") }, onClick = { citeMenu = false; cite("bibtex", "copy") })
                        DropdownMenuItem(text = { Text("Send BibTeX (.bib)") }, onClick = { citeMenu = false; cite("bibtex", "share") })
                        DropdownMenuItem(text = { Text("Copy RIS (Zotero, Mendeley)") }, onClick = { citeMenu = false; cite("ris", "copy") })
                        DropdownMenuItem(text = { Text("Send RIS (.ris)") }, onClick = { citeMenu = false; cite("ris", "share") })
                    }
                }
                OutlinedButton(onClick = { saveDialog = true }) { Icon(Icons.Filled.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Folders & flairs") }
                OutlinedButton(onClick = { sharePaper(ctx, paper) }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                if (paper.kind != "news") OutlinedButton(onClick = { openUrl(ctx, scholarUrl(paper)) }) { Icon(Icons.Filled.School, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scholar") }
            }
            busy?.let { Text(it, color = cs.outline, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant)
            SelectionContainer {
                Text(tex2text(abstract).ifEmpty { "No abstract in the feed — open the page." }, fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurface)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (saveDialog) FoldersDialog(vm, paper) { saveDialog = false }
}

@Composable
fun FoldersDialog(vm: AppViewModel, paper: Paper, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val current = state.saved[paper.id]
    val chosen = remember { mutableStateListOf<String>().apply { addAll(current?.folders ?: emptyList()) } }
    var newFolder by remember { mutableStateOf("") }
    var flairs by remember { mutableStateOf(current?.flairs?.joinToString(", ") ?: "") }
    val known = state.saved.values.flatMap { it.flairs }.distinct().sorted()
    AlertDialog(onDismissRequest = onClose, title = { Text("Folders & flairs") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Saving puts the paper in Saved. Pick folders and add your own tags.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            (state.folders + chosen).distinct().forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f in chosen, onCheckedChange = { if (it) chosen.add(f) else chosen.remove(f) })
                    Text(f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newFolder, { newFolder = it }, label = { Text("New folder") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (newFolder.isNotBlank()) { chosen.add(newFolder.trim()); newFolder = "" } }) { Icon(Icons.Filled.Add, "Add folder") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(flairs, { flairs = it }, label = { Text("My flair tags (comma separated)") },
                supportingText = { Text(if (known.isEmpty()) "e.g. must read, method, my field" else "used: " + known.joinToString(", ")) })
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (newFolder.isNotBlank()) chosen.add(newFolder.trim())
            vm.setFoldersAndFlairs(paper, chosen.distinct(), flairs.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            onClose()
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
