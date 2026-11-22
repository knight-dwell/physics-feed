package io.github.knightdwell.physicsfeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knightdwell.physicsfeed.data.Fetcher
import io.github.knightdwell.physicsfeed.data.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Search all of arXiv, all journals (Crossref) or Google Scholar. Results keep your topic tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: AppViewModel, onOpen: (Paper) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by vm.config.collectAsState()
    val state by vm.state.collectAsState()
    var src by rememberSaveable { mutableStateOf("arxiv") }
    var text by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf("all") }
    var topic by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var journal by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("relevance") }
    val results = remember { mutableStateListOf<Paper>() }
    var total by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("Search arXiv, or all journals (APS, IOP, Nature, Springer, Wiley, AIP… via Crossref), by words, author or ID / DOI.") }
    val listState = rememberLazyListState()
    val dark = isDark(state.theme)

    val fields = when (src) {
        "arxiv" -> listOf("all" to "All fields", "ti" to "Title", "au" to "Author", "abs" to "Abstract", "id" to "arXiv ID", "adv" to "Advanced")
        "journals" -> listOf("all" to "All fields", "au" to "Author", "id" to "DOI")
        else -> listOf("all" to "All fields", "au" to "Author")
    }
    if (fields.none { it.first == field }) field = "all"
    val topicOptions = buildList {
        add("" to "Any topic")
        vm.catalog.areas.keys.forEach { add("a:$it" to it) }
        config.topics.forEach { (g, tps) -> add("g:$g" to "$g (my tags)"); tps.keys.forEach { add("t:$it" to "   $it") } }
        val seen = config.topics.values.flatMap { it.keys }.toMutableSet()
        vm.catalog.fields.forEach { f ->
            val extra = f.topics.keys.filter { it !in seen }
            if (extra.isNotEmpty()) { add("f:${f.name}" to f.name); extra.forEach { t -> add("x:${f.name}|$t" to "   $t"); seen += t } }
        }
    }

    fun run(more: Boolean) {
        if (busy) return
        if (src == "scholar") {
            if (text.isBlank()) return
            val q = if (field == "au") "author:\"$text\"" else text
            openUrl(ctx, "https://scholar.google.com/scholar?q=" + URLEncoder.encode(q, "UTF-8")); return
        }
        if (text.isBlank() && topic.isEmpty()) return
        if (!more) { results.clear(); total = 0 }
        busy = true
        var (areaCat, kws) = vm.topicKeywords(topic)
        var extra = if (src == "arxiv") cat else journal
        if (src == "arxiv" && areaCat.isNotEmpty() && extra.isEmpty()) { extra = areaCat; kws = null }
        else if (src == "arxiv" && kws != null && extra.isEmpty()) {
            extra = (if (topic.startsWith("f:") || topic.startsWith("x:")) vm.catalog.fields.first { it.name == topic.substring(2).substringBefore("|") }.arxiv
            else config.arxivCategories).joinToString(",")
        }
        val start = results.size
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (src == "arxiv") Fetcher.searchArxiv(text, field, extra, sort, start, 50, kws)
                    else Fetcher.searchCrossref(text, field, extra, sort, start, 40, kws)
                }
            }
            r.onSuccess { (items, t) ->
                val comp = Fetcher.compileTopics(config.topics)
                val have = results.map { it.id }.toMutableSet()
                results.addAll(items.filter { have.add(it.id) }.map { it.copy(topics = Fetcher.match(it.title + " " + it.abstract, comp)) })
                total = t
                msg = if (results.isEmpty()) "No results." else ""
            }.onFailure { msg = "The search didn't answer: ${it.message}" }
            busy = false
        }
    }

    val pending by vm.pendingSearch.collectAsState()
    LaunchedEffect(pending) {   // a search typed in the side menu
        pending?.let { text = it; vm.pendingSearch.value = null; run(false) }
    }

    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd) { if (results.isNotEmpty() && nearEnd >= results.size - 3 && results.size < total && src != "scholar") run(true) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Search papers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { pad ->
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("arxiv" to "arXiv", "journals" to "Journals", "scholar" to "Scholar").forEachIndexed { i, (k, v) ->
                            SegmentedButton(selected = src == k, onClick = { src = k }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(v) }
                        }
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text(when (field) { "au" -> "e.g. Piers Coleman"; "id" -> if (src == "arxiv") "e.g. 2511.01234" else "a DOI"; else -> "e.g. slave boson Kondo" }) },
                        trailingIcon = { IconButton(onClick = { run(false) }) { Icon(Icons.Filled.Search, "Search") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run(false) }),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Choice("Field", fields, field) { field = it }
                        if (src != "scholar") Choice("Topic", topicOptions, topic) { topic = it }
                        if (src == "arxiv") Choice("Category", vm.catalog.arxivCategories, cat) { cat = it }
                        if (src == "journals") Choice("Journal", listOf("" to "Any journal") + vm.catalog.searchJournals.map { (n, issn) -> issn to n }, journal) { journal = it }
                        if (src != "scholar") Choice("Sort", listOf("relevance" to "Most relevant", "newest" to "Newest first"), sort) { sort = it }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (results.isNotEmpty()) Text("${results.size} of $total results", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 6.dp))
                    if (msg.isNotEmpty() && results.isEmpty()) Text(if (src == "scholar") "Google Scholar opens in your browser." else msg,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            items(results, key = { it.id }) { p ->
                PaperCard(p, state.saved[p.id], dark, false, onOpen = { onOpen(p) }, onStar = { vm.toggleSaved(p) })
            }
        }
    }
}
