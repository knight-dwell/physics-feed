package io.github.knightdwell.physicsfeed.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.knightdwell.physicsfeed.data.Catalog
import io.github.knightdwell.physicsfeed.data.Config
import io.github.knightdwell.physicsfeed.data.FeedData
import io.github.knightdwell.physicsfeed.data.Fetcher
import io.github.knightdwell.physicsfeed.data.JournalSpec
import io.github.knightdwell.physicsfeed.data.Paper
import io.github.knightdwell.physicsfeed.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the list shows: a fixed view, a topic, a group, a journal, a news group, a saved folder or flair. */
data class View(val kind: String, val key: String) {
    companion object {
        val ForYou = View("special", "foryou")
        val Today = View("special", "today")
        val Saved = View("special", "saved")
        val All = View("special", "all")
        val Open = View("special", "oa")
        val News = View("special", "news")
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val catalog = Catalog(app)
    private val store = Store(app)

    val firstRun = MutableStateFlow(!store.hasConfig())
    private val _config = MutableStateFlow(store.loadConfig())
    val config: StateFlow<Config> = _config
    private val _feed = MutableStateFlow(FeedData())        // filled in the background: the first screen shows at once
    val feed: StateFlow<FeedData> = _feed
    private val _state = MutableStateFlow(store.loadState())
    val state: StateFlow<Store.State> = _state
    val loading = MutableStateFlow(true)
    val images = MutableStateFlow<Map<String, String>>(emptyMap())   // url -> preview picture found on the page

    init {
        viewModelScope.launch(Dispatchers.Main) {   // dispatched (not immediate): runs after every field is set up
            val f = withContext(Dispatchers.IO) { store.loadFeed() }
            _feed.value = f
            loading.value = false
            if (!firstRun.value && f.items.isEmpty()) refresh()
        }
    }

    /** A news item without a picture: look for the page's preview image once, in the background. */
    fun wantImage(p: Paper) {
        if (p.image.isNotEmpty() || p.url in images.value || p.kind != "news") return
        images.value = images.value + (p.url to "")
        viewModelScope.launch {
            val u = withContext(Dispatchers.IO) { runCatching { Fetcher.ogImage(p.url) }.getOrDefault("") }
            if (u.isNotEmpty()) images.value = images.value + (p.url to u)
        }
    }

    val linkView = MutableStateFlow<View?>(null)      // from physicsfeed:// links
    val linkScreen = MutableStateFlow<String?>(null)
    val pendingSearch = MutableStateFlow<String?>(null)   // typed in the side menu, run by the Search screen

    /** Where to send people for a journal we couldn't fetch: its site (from the feed link) or a web search. */
    fun journalWebsite(name: String): String {
        if (name == "arXiv") return "https://arxiv.org/list/cond-mat/new"
        val spec = _config.value.journals[name]
        if (spec != null && spec.url.startsWith("http")) {
            val u = java.net.URL(spec.url); return "${u.protocol}://${u.host}"
        }
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode("$name journal latest articles", "UTF-8")
    }

    /** Download a paper's PDF (arXiv, or the publisher's link for open access) into the cache; null if there is none. */
    fun fetchPdf(p: Paper, done: (java.io.File?) -> Unit) {
        toast("Getting the PDF…")
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(getApplication<Application>().cacheDir, "pdf").apply { mkdirs() }
                    val file = java.io.File(dir, p.id.replace(Regex("[^A-Za-z0-9.]+"), "_") + ".pdf")
                    if (!file.exists()) {
                        val url = Fetcher.pdfLink(p).ifEmpty { throw RuntimeException("no PDF link") }
                        val bytes = Fetcher.getBytes(url)
                        if (bytes.size < 5 || String(bytes, 0, 4) != "%PDF") throw RuntimeException("not a PDF")
                        file.writeBytes(bytes)
                    }
                    file
                }.getOrNull()
            }
            done(f)
        }
    }

    fun setBuiltinPdf(on: Boolean) = setState(_state.value.copy(builtinPdf = on))
    fun setNewsCards(on: Boolean) = setState(_state.value.copy(newsCards = on))
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun toast(s: String?) { _message.value = s }

    // ---------------------------------------------------------------- lists
    fun items(v: View): List<Paper> {
        val all = _feed.value.items
        val today = LocalDate.now().toString()
        return when (v.kind) {
            "special" -> when (v.key) {
                "foryou" -> all.filter { it.topics.isNotEmpty() }
                "today" -> all.filter { it.firstSeen == today && it.kind != "news" }
                "saved" -> _state.value.saved.values.toList()
                "oa" -> all.filter { it.oa }
                "news" -> all.filter { it.kind == "news" }
                else -> all.filter { it.kind != "news" }
            }
            "topic" -> all.filter { v.key in it.topics }
            "group" -> { val names = _config.value.topics[v.key]?.keys ?: emptySet(); all.filter { it.topics.any { t -> t in names } } }
            "source" -> all.filter { it.source == v.key }
            "newsgroup" -> all.filter { it.kind == "news" && it.group == v.key }
            "folder" -> _state.value.saved.values.filter { v.key in it.folders }
            "flair" -> _state.value.saved.values.filter { v.key in it.flairs }
            else -> all
        }
    }

    fun title(v: View): String = when (v.kind) {
        "special" -> mapOf("foryou" to "For you", "today" to "New today", "saved" to "Saved", "all" to "Everything",
            "oa" to "Open access", "news" to "News")[v.key] ?: v.key
        "group" -> "All ${v.key.lowercase()}"
        "flair" -> "#${v.key}"
        else -> v.key
    }

    // ---------------------------------------------------------------- fetching
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { Fetcher.refresh(_config.value, _feed.value) { partial -> _feed.value = partial } }
            }
            res.onSuccess { (f, _) ->
                withContext(Dispatchers.IO) { store.saveFeed(f) }
                _feed.value = f
                toast("${f.items.size} papers · ${f.items.count { it.topics.isNotEmpty() }} in your topics")
            }.onFailure { toast("Couldn't fetch: ${it.message}") }
            _refreshing.value = false
        }
    }

    val loadingOlder = MutableStateFlow(false)

    /** "Load 2 more days": older papers from arXiv and Crossref journals. */
    fun loadOlder(days: Long = 2) {
        if (loadingOlder.value) return
        loadingOlder.value = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Fetcher.fetchOlder(_config.value, _feed.value, days) } }
            r.onSuccess { (f, added) ->
                _feed.value = f
                withContext(Dispatchers.IO) { store.saveFeed(f) }
                toast(if (added > 0) "$added older papers added" else "Nothing older found for those days")
            }.onFailure { toast("Couldn't load older papers: ${it.message}") }
            loadingOlder.value = false
        }
    }

    fun oldestDate(): String = _feed.value.items.filter { it.kind != "news" && it.date.isNotEmpty() }.minOfOrNull { it.date } ?: ""

    fun loadFullAbstract(p: Paper, done: (String) -> Unit) {
        val cut = p.kind != "news" && (p.abstract.isEmpty() || p.abstract.endsWith("…") || p.abstract.endsWith("..."))
        if (!cut) return
        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) { runCatching { Fetcher.fullAbstract(p) }.getOrDefault("") }
            if (full.length > p.abstract.trimEnd('…', '.').length) {
                _feed.value = _feed.value.copy(items = _feed.value.items.map { if (it.id == p.id) it.copy(abstract = full) else it })
                done(full)
            }
        }
    }

    // ---------------------------------------------------------------- saved, folders, flairs
    fun isSaved(p: Paper) = p.id in _state.value.saved

    private fun setState(s: Store.State) { _state.value = s; viewModelScope.launch(Dispatchers.IO) { store.saveState(s) } }

    fun toggleSaved(p: Paper) {
        val s = _state.value
        setState(s.copy(saved = if (p.id in s.saved) s.saved - p.id else s.saved + (p.id to p.copy(folders = emptyList(), flairs = emptyList()))))
    }

    fun savedCopy(p: Paper) = _state.value.saved[p.id]

    fun setFoldersAndFlairs(p: Paper, folders: List<String>, flairs: List<String>) {
        val s = _state.value
        val base = s.saved[p.id] ?: p
        val newFolders = (s.folders + folders).distinct()
        setState(s.copy(saved = s.saved + (p.id to base.copy(folders = folders, flairs = flairs)), folders = newFolders))
    }

    fun renameFolder(old: String, new: String?) {
        val s = _state.value
        val saved = s.saved.mapValues { (_, p) ->
            if (old in p.folders) p.copy(folders = p.folders.filter { it != old } + listOfNotNull(new?.takeIf { it.isNotBlank() })) else p
        }
        val folders = s.folders.mapNotNull { if (it == old) new?.takeIf { n -> n.isNotBlank() } else it }.distinct()
        setState(s.copy(saved = saved, folders = folders))
    }

    fun setTheme(t: String) = setState(_state.value.copy(theme = t))

    // ---------------------------------------------------------------- updates (GitHub releases)
    val updateReady = MutableStateFlow<Pair<io.github.knightdwell.physicsfeed.data.Updater.Release, java.io.File?>?>(null)
    val updateStatus = MutableStateFlow("")

    fun setAutoUpdate(on: Boolean) = setState(_state.value.copy(autoUpdate = on))

    /** auto = called at start: only if the switch is on and the last check is over 12 h old; downloads straight away. */
    fun checkUpdate(auto: Boolean) {
        val s = _state.value
        if (auto) {
            if (!s.autoUpdate) return
            val last = runCatching { java.time.LocalDateTime.parse(s.updateChecked) }.getOrNull()
            if (last != null && last.plusHours(12).isAfter(java.time.LocalDateTime.now())) return
        }
        updateStatus.value = "checking…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { io.github.knightdwell.physicsfeed.data.Updater.latest() } }
            setState(_state.value.copy(updateChecked = java.time.LocalDateTime.now().toString().take(16)))
            val rel = r.getOrNull()
            when {
                r.isFailure -> updateStatus.value = "Couldn't reach GitHub"
                rel == null || !io.github.knightdwell.physicsfeed.data.Updater.isNewer(rel.version) -> updateStatus.value = "You have the newest version."
                else -> {
                    updateStatus.value = "Version ${rel.version} is available"
                    val apk = if (auto) withContext(Dispatchers.IO) {
                        runCatching { io.github.knightdwell.physicsfeed.data.Updater.download(getApplication(), rel) }.getOrNull()
                    } else null
                    updateReady.value = rel to apk
                }
            }
        }
    }

    fun downloadAndInstall(rel: io.github.knightdwell.physicsfeed.data.Updater.Release, have: java.io.File?) {
        updateStatus.value = "downloading…"
        viewModelScope.launch {
            val f = have ?: withContext(Dispatchers.IO) {
                runCatching { io.github.knightdwell.physicsfeed.data.Updater.download(getApplication(), rel) }.getOrNull()
            }
            if (f == null) { updateStatus.value = "Download failed"; return@launch }
            updateStatus.value = "Android will ask you to confirm the install."
            io.github.knightdwell.physicsfeed.data.Updater.install(getApplication(), f)
        }
    }

    fun markClosed() { _state.value = _state.value.copy(lastOpen = java.time.LocalDateTime.now().toString().take(16)); store.saveState(_state.value) }

    // ---------------------------------------------------------------- settings
    fun saveConfig(c: Config) {
        _config.value = c
        viewModelScope.launch(Dispatchers.IO) { store.saveConfig(c) }
        retag()
    }

    private fun retag() {
        val comp = Fetcher.compileTopics(_config.value.topics)
        _feed.value = _feed.value.copy(items = _feed.value.items.map { if (it.kind == "news") it else it.copy(topics = Fetcher.match(it.title + " " + it.abstract, comp)) })
        viewModelScope.launch(Dispatchers.IO) { store.saveFeed(_feed.value) }
    }

    fun setupFields(fields: List<String>, followNews: Boolean) {
        val chosen = catalog.fields.filter { it.name in fields }.ifEmpty { catalog.fields.take(1) }
        val topics = linkedMapOf<String, Map<String, List<String>>>()
        val cats = mutableListOf<String>()
        val journals = linkedMapOf<String, JournalSpec>()
        for (f in chosen) {
            topics[f.name] = f.topics
            f.arxiv.forEach { if (it !in cats) cats += it }
            (f.journals + listOf("PRL", "PRB", "PRX", "Rev. Mod. Phys.")).forEach { j -> catalog.journal(j)?.let { journals.getOrPut(j) { it.spec() } } }
        }
        val c = Config(topics = topics, arxivCategories = cats, journals = journals, news = if (followNews) catalog.defaultNews else emptyMap())
        _config.value = c
        viewModelScope.launch(Dispatchers.IO) { store.saveConfig(c) }
        firstRun.value = false
        refresh()
    }

    fun exportConfigText(): String = _config.value.toJson().toString(2)

    fun importConfig(text: String) {
        runCatching { store.importConfig(text) }
            .onSuccess { _config.value = it; firstRun.value = false; retag(); toast("Settings imported"); refresh() }
            .onFailure { toast("That isn't a Physics Feed settings file") }
    }

    fun topicKeywords(id: String): Pair<String, List<String>?> {
        if (id.isEmpty()) return "" to null
        val kind = id[0]; val key = id.substring(2)
        return when (kind) {
            'a' -> catalog.areas[key]!!.let { it.first to it.second }
            'g' -> "" to (_config.value.topics[key]?.values?.flatten() ?: emptyList())
            't' -> "" to _config.value.topics.values.firstNotNullOfOrNull { it[key] }
            'f' -> "" to (catalog.fields.first { it.name == key }.topics.values.flatten())
            'x' -> key.split("|", limit = 2).let { (f, t) -> "" to catalog.fields.first { it.name == f }.topics[t] }
            else -> "" to null
        }
    }
}
