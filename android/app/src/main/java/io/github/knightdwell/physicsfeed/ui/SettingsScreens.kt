package io.github.knightdwell.physicsfeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knightdwell.physicsfeed.data.JournalSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun Head(t: String) = Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

// ------------------------------------------------------------------ My topics
@Composable
fun TopicsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    var edit by remember { mutableStateOf<Triple<String, String, String>?>(null) }   // group, topic, keywords
    var addField by remember { mutableStateOf(false) }
    Scaffold(topBar = { Bar("My topics", onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Papers get a tag when a keyword appears in the title or abstract. Keywords: comma separated; short ALL-CAPS words " +
                    "(DMFT, SSH) match whole words; a&b means both must appear.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { addField = true }) { Icon(Icons.Filled.Add, null); Text(" Add a field") }
                    FilledTonalButton(onClick = { edit = Triple("My research", "", "") }) { Text("New topic") }
                }
            }
            config.topics.forEach { (g, tps) ->
                item(key = "g$g") { Head(g) }
                items(tps.entries.toList(), key = { "t$g/${it.key}" }) { (t, kws) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t, fontWeight = FontWeight.SemiBold)
                            Text(kws.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { edit = Triple(g, t, kws.joinToString(", ")) }) { Icon(Icons.Filled.Edit, "Edit") }
                        IconButton(onClick = {
                            val ng = config.topics.toMutableMap()
                            val inner = (ng[g] ?: emptyMap()) - t
                            if (inner.isEmpty()) ng.remove(g) else ng[g] = inner
                            vm.saveConfig(config.copy(topics = ng))
                        }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
        }
    }
    edit?.let { (g0, t0, k0) ->
        var g by remember(edit) { mutableStateOf(g0) }
        var t by remember(edit) { mutableStateOf(t0) }
        var k by remember(edit) { mutableStateOf(k0) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (t0.isEmpty()) "New topic" else "Edit topic") }, text = {
            Column {
                OutlinedTextField(g, { g = it }, label = { Text("Group") }, singleLine = true)
                OutlinedTextField(t, { t = it }, label = { Text("Topic (tag) name") }, singleLine = true)
                OutlinedTextField(k, { k = it }, label = { Text("Keywords, comma separated") }, minLines = 3)
            }
        }, confirmButton = {
            TextButton(onClick = {
                val kws = k.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (t.isNotBlank() && kws.isNotEmpty()) {
                    val ng = config.topics.toMutableMap()
                    if (t0.isNotEmpty()) { val inner = (ng[g0] ?: emptyMap()) - t0; if (inner.isEmpty()) ng.remove(g0) else ng[g0] = inner }
                    val grp = g.ifBlank { "My research" }
                    ng[grp] = (ng[grp] ?: emptyMap()) + (t.trim() to kws)
                    vm.saveConfig(config.copy(topics = ng))
                }
                edit = null
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    if (addField) FieldPicker(vm) { addField = false }
}

@Composable
fun FieldPicker(vm: AppViewModel, onClose: () -> Unit) {
    val config by vm.config.collectAsState()
    var idx by remember { mutableIntStateOf(0) }
    val field = vm.catalog.fields[idx]
    val pick = remember(idx) { mutableStateMapOf<String, Boolean>().apply { field.topics.keys.forEach { put(it, true) } } }
    val jpick = remember(idx) { mutableStateMapOf<String, Boolean>().apply { field.journals.forEach { put(it, it !in config.journals) } } }
    AlertDialog(onDismissRequest = onClose, title = { Text("Add a field") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Choice("Field", vm.catalog.fields.mapIndexed { i, f -> "$i" to f.name }, "$idx") { idx = it.toInt() }
            Head("Tags")
            field.topics.forEach { (t, kws) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pick[t] == true, { pick[t] = it })
                    Column { Text(t); Text(kws.joinToString(", "), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Head("Journals for this field")
            field.journals.filter { vm.catalog.journal(it) != null }.forEach { j ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(jpick[j] == true, { jpick[j] = it }, enabled = j !in config.journals)
                    Text(j + (if (j in config.journals) "  (following)" else "") + (if (vm.catalog.isOpenAccess(j)) "  🔓" else ""))
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val topics = config.topics.toMutableMap()
            topics[field.name] = (topics[field.name] ?: emptyMap()) + field.topics.filterKeys { pick[it] == true }
            val js = config.journals.toMutableMap()
            jpick.filter { it.value }.keys.forEach { j -> vm.catalog.journal(j)?.let { js.putIfAbsent(j, it.spec()) } }
            val cats = (config.arxivCategories + field.arxiv).distinct()
            vm.saveConfig(config.copy(topics = topics, journals = js, arxivCategories = cats))
            onClose()
        }) { Text("Add") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// ------------------------------------------------------------------ Journals & news
@Composable
fun JournalsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { Bar("Journals & news", onBack) }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Journals", "News", "＋ Add your own").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    item { Text("Tick to follow. 🔓 = open access. \"Only my topics\" keeps just matching papers (good for big journals).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
                    val custom = config.journals.keys.filter { vm.catalog.journal(it) == null }
                    val groups = vm.catalog.journals.groupBy { it.group }.mapValues { e -> e.value.map { it.name } }.toMutableMap()
                    if (custom.isNotEmpty()) groups["Added by you"] = custom
                    groups.forEach { (g, names) ->
                        item(key = "jg$g") { Head(g) }
                        items(names, key = { "j$it" }) { n ->
                            val on = n in config.journals
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, { v ->
                                    val js = config.journals.toMutableMap()
                                    if (v) vm.catalog.journal(n)?.let { js[n] = it.spec() } else js.remove(n)
                                    vm.saveConfig(config.copy(journals = js))
                                })
                                Text(n + if (vm.catalog.isOpenAccess(n)) "  🔓" else "", Modifier.weight(1f))
                                if (on) {
                                    Text("only my topics", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    Switch(config.journals[n]?.onlyMyTopics == true, { v ->
                                        vm.saveConfig(config.copy(journals = config.journals + (n to config.journals[n]!!.copy(onlyMyTopics = v))))
                                    }, Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
                1 -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    item { Text("News has its own section, not mixed with papers.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
                    val all = vm.catalog.news.map { Triple(it.name, it.group, it.url) } +
                        config.news.filter { (n, _) -> vm.catalog.news.none { it.name == n } }.map { (n, p) -> Triple(n, p.second, p.first) }
                    all.groupBy { it.second }.forEach { (g, list) ->
                        item(key = "ng$g") { Head(g) }
                        items(list, key = { "n${it.first}" }) { (n, grp, url) ->
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(n in config.news, { v ->
                                    vm.saveConfig(config.copy(news = if (v) config.news + (n to (url to grp)) else config.news - n))
                                })
                                Text(n)
                            }
                        }
                    }
                }
                else -> AddOwn(vm)
            }
        }
    }
}

@Composable
private fun AddOwn(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    var name by remember { mutableStateOf("") }
    var src by remember { mutableStateOf("") }
    var news by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("") }
    var oa by remember { mutableStateOf(false) }
    var only by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Paste one of these:\n" +
            "• an RSS / Atom feed link — on the journal or news site look for the RSS icon, \"Subscribe\" or \"Feeds\" and copy that link " +
            "(APS: http://feeds.aps.org/rss/recent/prl.xml)\n" +
            "• or the journal's ISSN (like 1361-648X) — on the journal's \"About\" page or its Wikipedia page. This works for every " +
            "publisher (IOP, Springer, Elsevier, Nature, ACS…).", fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(src, { src = it }, label = { Text("Feed link or ISSN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(news, { news = it }); Text("  It's a news site, not a journal") }
        if (news) OutlinedTextField(group, { group = it }, label = { Text("News group (e.g. Physics news)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        else {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(oa, { oa = it }); Text("Open access (free to read)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(only, { only = it }); Text("Only papers matching my topics") }
        }
        Button(onClick = {
            val s = src.trim()
            val issn = Regex("^\\d{4}-?\\d{3}[\\dXx]$").matches(s)
            when {
                name.isBlank() || s.isBlank() -> msg = "Give a name and a link or ISSN."
                news && !s.startsWith("http") -> msg = "News needs a feed link."
                news -> { vm.saveConfig(config.copy(news = config.news + (name.trim() to (s to group.ifBlank { "My news" })))); msg = "Added \"$name\". Pull to refresh to fetch it."; name = ""; src = "" }
                issn -> {
                    val i = if ('-' in s) s.uppercase() else s.substring(0, 4) + "-" + s.substring(4).uppercase()
                    vm.saveConfig(config.copy(journals = config.journals + (name.trim() to JournalSpec("crossref", only, i, oa)))); msg = "Added \"$name\"."; name = ""; src = ""
                }
                s.startsWith("http") -> { vm.saveConfig(config.copy(journals = config.journals + (name.trim() to JournalSpec(s, only, "", oa)))); msg = "Added \"$name\"."; name = ""; src = "" }
                else -> msg = "That's neither a link (http…) nor an ISSN (1234-567X)."
            }
        }, modifier = Modifier.padding(top = 12.dp)) { Text("Add") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
}

// ------------------------------------------------------------------ first start
@Composable
fun WelcomeScreen(vm: AppViewModel, onImport: () -> Unit) {
    val chosen = remember { mutableStateListOf<String>() }
    var news by remember { mutableStateOf(true) }
    Scaffold { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
            item {
                Text("Welcome to Physics Feed", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Pick the fields you work in. The app then follows the right arXiv categories and journals and sorts new papers " +
                    "into tags for you. You can change everything later.", modifier = Modifier.padding(vertical = 12.dp))
            }
            items(vm.catalog.fields, key = { it.name }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Checkbox(f.name in chosen, { if (it) chosen.add(f.name) else chosen.remove(f.name) })
                    Column {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text(f.topics.keys.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(news, { news = it }); Text("Also physics news (APS Physics, Physics World, Quanta)")
                }
                Button(onClick = { vm.setupFields(chosen.toList(), news) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Start") }
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("…or import settings a colleague sent you") }
            }
        }
    }
}

// ------------------------------------------------------------------ folders, help
@Composable
fun ManageFoldersDialog(vm: AppViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    var rename by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Folders") }, text = {
        Column {
            if (state.folders.isEmpty()) Text("No folders yet. Open a paper → Folders & flairs to make one.")
            state.folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f, Modifier.weight(1f))
                    IconButton(onClick = { rename = f; newName = f }) { Icon(Icons.Filled.Edit, "Rename") }
                    IconButton(onClick = { vm.renameFolder(f, null) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            rename?.let {
                OutlinedTextField(newName, { newName = it }, label = { Text("New name for \"$it\"") }, singleLine = true)
                TextButton(onClick = { vm.renameFolder(it, newName); rename = null }) { Text("Rename") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } })
}

@Composable
fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("How to use Physics Feed") }, text = {
        Text(
            "• New papers from arXiv and journals arrive about twice a day; pull down to fetch now.\n" +
                "• ☰ opens your topics, journals, news and saved folders. The chips on top are shortcuts.\n" +
                "• Tap a paper: full abstract, Read PDF, Journal page, Cite (BibTeX/RIS), Share, Scholar.\n" +
                "• ☆ saves a paper. \"Folders & flairs\" puts it in folders and gives it your own #tags.\n" +
                "• 🔍 searches all of arXiv, all journals (Crossref) or Google Scholar, by words, author or ID, with a Topic filter.\n" +
                "• ⋮ → My topics: add a field and tick its tags, or edit keywords.  Journals & news: choose what to follow or add your own.\n" +
                "• ⋮ → Export settings sends your topics and journals to a colleague; they import them on their phone or the desktop app.",
            fontSize = 14.sp, lineHeight = 20.sp,
        )
    }, confirmButton = { TextButton(onClick = onClose) { Text("Got it") } })
}

@Composable
fun UpdatesDialog(vm: AppViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val status by vm.updateStatus.collectAsState()
    val ready by vm.updateReady.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.checkUpdate(auto = false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Updates") }, text = {
        Column {
            Text("Physics Feed ${io.github.knightdwell.physicsfeed.BuildConfig.VERSION_NAME}\nUpdates come from github.com/${io.github.knightdwell.physicsfeed.data.Updater.REPO}",
                fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Switch(state.autoUpdate, { vm.setAutoUpdate(it) })
                Text("  Update automatically\n  (checks twice a day, downloads new versions)", fontSize = 13.sp)
            }
            Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            ready?.let { (rel, _) -> if (rel.notes.isNotBlank()) Text(rel.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 6) }
        }
    }, confirmButton = {
        val r = ready
        if (r != null) TextButton(onClick = { vm.downloadAndInstall(r.first, r.second) }) { Text("Update now") }
        else TextButton(onClick = { vm.checkUpdate(auto = false) }) { Text("Check now") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}

/** Feed status: every source with how many papers it gave and, if it failed, why. */
@Composable
fun StatusDialog(vm: AppViewModel, onClose: () -> Unit) {
    val feed by vm.feed.collectAsState()
    val config by vm.config.collectAsState()
    val sources = listOf("arXiv") + config.journals.keys + config.news.keys
    AlertDialog(onDismissRequest = onClose, title = { Text("Feed status") }, text = {
        LazyColumn {
            item {
                Text("Updated ${feed.updated.ifEmpty { "never" }} · ${feed.items.size} items · arXiv categories: " +
                    config.arxivCategories.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            items(sources) { s ->
                val n = feed.items.count { it.source == s }
                val err = feed.errors[s]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(s, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (err != null && n == 0) "✗" else "$n", fontSize = 13.sp,
                        color = if (err != null && n == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                if (err != null) Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
