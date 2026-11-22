package io.github.knightdwell.physicsfeed.data

import android.text.Html
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Network side: arXiv API, RSS/Atom feeds, Crossref, citations. A port of the desktop feed.py. */
object Fetcher {
    const val KEEP_DAYS = 6L
    private const val UA = "Mozilla/5.0 (Android) physics-feed/1.0"

    // ------------------------------------------------------------------ http
    fun get(url: String, accept: String? = null, timeoutMs: Int = 40_000): String {
        var u = url
        repeat(5) {   // follow redirects, also http -> https
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            if (accept != null) c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw RuntimeException("redirect without location")
                u = URL(URL(u), loc).toString(); c.disconnect(); return@repeat
            }
            if (code >= 400) { c.disconnect(); throw RuntimeException("HTTP $code") }
            return c.inputStream.bufferedReader().use { it.readText() }
        }
        throw RuntimeException("too many redirects")
    }

    fun getBytes(url: String): ByteArray {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 40_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code >= 400) throw RuntimeException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        }
        throw RuntimeException("too many redirects")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val IMG = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val OG = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val OG2 = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"']", RegexOption.IGNORE_CASE)

    /** The page's preview picture (og:image) for news without a picture in the feed. Reads only the page head. */
    fun ogImage(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream.use { ins ->
            val head = String(ins.readNBytesCompat(96 * 1024))
            (OG.find(head) ?: OG2.find(head))?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(8192); var total = 0
        while (total < n) { val r = read(b, 0, minOf(b.size, n - total)); if (r < 0) break; buf.write(b, 0, r); total += r }
        return buf.toByteArray()
    }

    // ------------------------------------------------------------------ text helpers
    fun clean(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val t = Html.fromHtml(s.replace(Regex("<[^>]+>"), " "), Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun findDoi(vararg texts: String?): String {
        for (t in texts) {
            val m = Regex("10\\.\\d{4,9}/[^\\s\"<>?#]+").find(t ?: "") ?: continue
            return m.value.trimEnd('.', ',', ';', ')')
        }
        return ""
    }

    fun parseDate(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val t = s.trim()
        runCatching { return OffsetDateTime.parse(t).toLocalDate().toString() }
        runCatching { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString() }
        return Regex("\\d{4}-\\d{2}-\\d{2}").find(t)?.value ?: ""
    }

    // ------------------------------------------------------------------ topics
    class Compiled(val name: String, val patterns: List<List<Regex>>)

    fun compileTopics(topics: Map<String, Map<String, List<String>>>): List<Compiled> =
        topics.values.flatMap { it.entries }.map { (name, kws) -> Compiled(name, kws.map { kw -> kw.split("&").mapNotNull { rx(it.trim()) } }) }

    private fun rx(p: String): Regex? {
        if (p.isEmpty()) return null
        val upper = p == p.uppercase() && p.any { it.isLetter() }
        val whole = p.length <= 5 || (upper && p.length <= 8)
        val body = Regex.escape(p)
        val pat = if (whole) "(?<![A-Za-z0-9])$body(?![A-Za-z0-9])" else body
        return if (upper) Regex(pat) else Regex(pat, RegexOption.IGNORE_CASE)
    }

    fun match(text: String, compiled: List<Compiled>): List<String> =
        compiled.filter { c -> c.patterns.any { parts -> parts.isNotEmpty() && parts.all { it.containsMatchIn(text) } } }.map { it.name }

    // ------------------------------------------------------------------ RSS / Atom
    fun fetchRss(name: String, url: String): List<Paper> {
        val xml = get(url)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        val out = mutableListOf<Paper>()
        var inItem = false; var inAuthor = false
        var fields = mutableMapOf<String, String>(); var authors = mutableListOf<String>()
        while (true) {
            val ev = try { p.next() } catch (e: Exception) { break }
            if (ev == XmlPullParser.END_DOCUMENT) break
            val tag = p.name ?: ""
            when (ev) {
                XmlPullParser.START_TAG -> when {
                    tag == "item" || tag == "entry" -> { inItem = true; fields = mutableMapOf(); authors = mutableListOf() }
                    inItem && tag == "author" -> inAuthor = true
                    inItem && (tag == "thumbnail" || (tag == "content" && p.getAttributeValue(null, "url") != null) ||
                        (tag == "enclosure" && (p.getAttributeValue(null, "type") ?: "").startsWith("image"))) -> {
                        val u = p.getAttributeValue(null, "url")
                        if (!u.isNullOrEmpty() && !fields.containsKey("image")) fields["image"] = u
                    }
                    inItem && tag == "link" && p.getAttributeValue(null, "href") != null -> {
                        val rel = p.getAttributeValue(null, "rel") ?: "alternate"
                        if (rel == "alternate" && !fields.containsKey("link")) fields["link"] = p.getAttributeValue(null, "href")
                    }
                    inItem -> {
                        val text = runCatching { p.nextText() }.getOrDefault("").trim()
                        if (text.isNotEmpty()) {
                            if (tag == "creator" || (inAuthor && tag == "name")) authors += clean(text)
                            else if (!fields.containsKey(tag)) fields[tag] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "author" -> inAuthor = false
                    "item", "entry" -> {
                        inItem = false
                        rssItem(name, fields, authors)?.let { out += it }
                    }
                }
            }
        }
        return out
    }

    private fun rssItem(name: String, f: Map<String, String>, authorsIn: List<String>): Paper? {
        val title = clean(f["title"])
        val link = f["link"] ?: f["id"] ?: return null
        if (title.isEmpty()) return null
        var authors = authorsIn
        if (authors.size == 1 && (authors[0].contains(",") || authors[0].contains(" and "))) {
            authors = authors[0].split(Regex(",| and ")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val raw = f["description"] ?: f["summary"] ?: f["encoded"] ?: f["abstract"] ?: ""
        var abs = clean(raw)
        if (abs.startsWith(name)) abs = abs.replace(Regex("^.*?doi:\\S+\\s*"), "")
        if (raw.trimStart().startsWith("Author(s):") && raw.contains("<br")) abs = clean(raw.substringAfter("<br").substringAfter(">"))
        abs = abs.replace(Regex("\\s*\\[Phys\\. Rev\\..*$"), "")
        abs = abs.replace(Regex("^[A-Z][\\w .&]+, Volume \\d+, Issue \\d+[^.]*\\.\\s*"), "")
        val date = parseDate(f["date"] ?: f["pubDate"] ?: f["published"] ?: f["updated"] ?: f["issued"])
        val img = f["image"] ?: IMG.find(raw + " " + (f["encoded"] ?: ""))?.groupValues?.get(1) ?: ""
        return Paper(
            id = link, source = name, kind = "journal", title = title, authors = authors.take(6),
            abstract = abs.take(6000), url = link, doi = findDoi(f["doi"], f["identifier"], link),
            date = date.ifEmpty { LocalDate.now().toString() }, image = img.replace("&amp;", "&"),
        )
    }

    // ------------------------------------------------------------------ Crossref
    private const val CR_FIELDS = "DOI,title,author,published,published-online,issued,abstract,container-title,type,link"

    private fun crossrefItem(w: JSONObject, source: String? = null): Paper {
        val doi = w.optString("DOI")
        val dp = (w.optJSONObject("published") ?: w.optJSONObject("published-online") ?: w.optJSONObject("issued"))
            ?.optJSONArray("date-parts")?.optJSONArray(0)
        val parts = if (dp == null) emptyList() else (0 until dp.length()).map { dp.optInt(it) }.filter { it > 0 }
        val date = when (parts.size) {
            0 -> LocalDate.now().toString()
            1 -> "%04d-01-01".format(parts[0])
            2 -> "%04d-%02d-01".format(parts[0], parts[1])
            else -> "%04d-%02d-%02d".format(parts[0], parts[1], parts[2])
        }
        val authors = w.optJSONArray("author")?.let { a ->
            (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                listOf(x.optString("given"), x.optString("family")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { x.optString("name") }
            }
        } ?: emptyList()
        val title = clean((w.optJSONArray("title")?.strings() ?: emptyList()).joinToString(" "))
        val cont = w.optJSONArray("container-title")?.strings()?.firstOrNull() ?: "Journal"
        return Paper(
            id = "doi:" + doi.lowercase(), source = source ?: cont, kind = "journal", title = title,
            authors = authors.take(6), abstract = clean(w.optString("abstract")).removePrefix("Abstract").trim().take(6000),
            url = "https://doi.org/$doi", doi = doi, date = date,
        )
    }

    fun fetchCrossref(name: String, issn: String, since: String): List<Paper> {
        val q = "filter=${enc("from-pub-date:$since,type:journal-article")}&sort=published&order=desc&rows=100&select=$CR_FIELDS"
        val items = JSONObject(get("https://api.crossref.org/journals/$issn/works?$q")).getJSONObject("message").getJSONArray("items")
        return (0 until items.length()).map { crossrefItem(items.getJSONObject(it), name) }.filter { it.title.isNotEmpty() }
    }

    fun searchCrossref(text: String, field: String, issn: String, sort: String, start: Int, n: Int, topicKws: List<String>?): Pair<List<Paper>, Int> {
        if (field == "id") {
            val doi = findDoi(text).ifEmpty { text.trim() }
            val w = JSONObject(get("https://api.crossref.org/works/" + enc(doi))).getJSONObject("message")
            return listOf(crossrefItem(w)) to 1
        }
        var t = text
        var rows = n
        val extra = StringBuilder()
        if (!topicKws.isNullOrEmpty()) {
            val plain = topicKws.take(6).joinToString(" ") { it.replace("&", " ") }
            if (field == "au") extra.append("&query.bibliographic=").append(enc(plain)) else t = "$t $plain".trim()
            rows = 100
        }
        if (field == "au") rows = minOf(n * 3, 200)
        val key = if (field == "au") "query.author" else "query.bibliographic"
        var url = "https://api.crossref.org/works?rows=$rows&offset=$start&select=$CR_FIELDS" +
            "&filter=" + enc("type:journal-article" + if (issn.isNotEmpty()) ",issn:$issn" else "") + "&$key=" + enc(t) + extra
        if (sort == "newest") url += "&sort=published&order=desc"
        val msg = JSONObject(get(url, timeoutMs = 60_000)).getJSONObject("message")
        val arr = msg.getJSONArray("items")
        var works = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { (it.optJSONArray("title")?.length() ?: 0) > 0 }
        if (field == "au") {
            val wanted = text.split(Regex("[,;]| and ")).mapNotNull { nm ->
                val ws = nm.split(" ").map { it.trim('.').lowercase() }.filter { it.isNotEmpty() }
                if (ws.isEmpty()) null else ws.last() to (if (ws.size > 1) ws.first().take(1) else "")
            }
            works = works.filter { w ->
                val au = w.optJSONArray("author") ?: return@filter false
                wanted.all { (last, ini) ->
                    (0 until au.length()).any { i ->
                        val a = au.getJSONObject(i)
                        a.optString("family").lowercase() == last && (ini.isEmpty() || a.optString("given").lowercase().startsWith(ini))
                    }
                }
            }
        }
        var papers = works.map { crossrefItem(it) }
        if (!topicKws.isNullOrEmpty()) {
            val comp = compileTopics(mapOf("t" to mapOf("t" to topicKws)))
            papers = papers.filter { match(it.title + " " + it.abstract, comp).isNotEmpty() }
            return papers to papers.size
        }
        return papers to (if (field == "au") papers.size else msg.optInt("total-results"))
    }

    // ------------------------------------------------------------------ arXiv
    private fun parseArxiv(xml: String): Pair<List<Paper>, Int> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        val out = mutableListOf<Paper>()
        var total = 0
        var inEntry = false; var inAuthor = false
        var f = mutableMapOf<String, String>(); var authors = mutableListOf<String>()
        while (true) {
            val ev = try { p.next() } catch (e: Exception) { break }
            if (ev == XmlPullParser.END_DOCUMENT) break
            val tag = p.name ?: ""
            if (ev == XmlPullParser.START_TAG) {
                when {
                    tag == "totalResults" -> total = runCatching { p.nextText().trim().toInt() }.getOrDefault(0)
                    tag == "entry" -> { inEntry = true; f = mutableMapOf(); authors = mutableListOf() }
                    inEntry && tag == "author" -> inAuthor = true
                    inEntry && tag == "primary_category" -> f["cat"] = p.getAttributeValue(null, "term") ?: ""
                    inEntry && tag == "link" -> Unit
                    inEntry && tag == "category" -> Unit
                    inEntry -> {
                        val t = runCatching { p.nextText() }.getOrDefault("")
                        if (inAuthor && tag == "name") authors += t.trim()
                        else if (p.namespace?.contains("arxiv") == true && tag == "doi") f["doi"] = t.trim()
                        else if (!f.containsKey(tag)) f[tag] = t
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (tag == "author") inAuthor = false
                if (tag == "entry") {
                    inEntry = false
                    val title = (f["title"] ?: "").replace(Regex("\\s+"), " ").trim()
                    if (title.isNotEmpty() && title != "Error") {
                        val pid = (f["id"] ?: "").substringAfter("/abs/").replace(Regex("v\\d+$"), "")
                        out += Paper(
                            id = "arxiv:$pid", source = "arXiv", kind = "arxiv", category = f["cat"] ?: "", title = title,
                            authors = authors.take(6), abstract = (f["summary"] ?: "").replace(Regex("\\s+"), " ").trim(),
                            url = "https://arxiv.org/abs/$pid", pdf = "https://arxiv.org/pdf/$pid", doi = f["doi"] ?: "",
                            date = (f["published"] ?: "").take(10),
                        )
                    }
                }
            }
        }
        return out to total
    }

    private fun catClause(cats: List<String>): String {
        val cs = cats.filter { it.isNotBlank() }
        val c = cs.joinToString(" OR ") { if ('.' in it) "cat:$it" else "cat:$it*" }
        return if (cs.size > 1) "($c)" else c
    }

    fun fetchArxiv(cats: List<String>, since: String, page: Int = 1000, maxPages: Int = 5): List<Paper> {
        if (cats.isEmpty()) return emptyList()
        val q = "${catClause(cats)} AND submittedDate:[${since.replace("-", "")}0000 TO 209912312359]"
        val out = mutableListOf<Paper>()
        for (n in 0 until maxPages) {
            val url = "https://export.arxiv.org/api/query?search_query=${enc(q)}&sortBy=submittedDate&sortOrder=descending&start=${n * page}&max_results=$page"
            var got = emptyList<Paper>()
            for (attempt in 0 until 3) {
                got = parseArxiv(get(url, timeoutMs = 120_000)).first
                if (got.isNotEmpty()) break
                Thread.sleep(5000)
            }
            out += got
            if (got.size < page) break
            Thread.sleep(3000)
        }
        return out
    }

    private fun topicClause(kws: List<String>): String {
        val ors = kws.take(18).mapNotNull { kw ->
            val parts = kw.split("&").map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> null
                parts.size == 1 -> "all:\"${parts[0]}\""
                else -> "(" + parts.joinToString(" AND ") { "all:\"$it\"" } + ")"
            }
        }
        return if (ors.isEmpty()) "" else "(" + ors.joinToString(" OR ") + ")"
    }

    fun buildQuery(text: String, field: String, cat: String, topicKws: List<String>?): String {
        val t = text.trim()
        var q = when (field) {
            "adv" -> t
            "au" -> t.split(Regex("[,;]| and ")).mapNotNull { nm ->
                val ws = nm.split(" ").map { it.trim('.') }.filter { it.isNotEmpty() }
                when {
                    ws.isEmpty() -> null
                    ws.size == 1 -> "au:${ws[0]}"
                    ws[0].length <= 2 -> "au:${ws.last()}_${ws[0][0]}"
                    else -> "au:\"${ws.joinToString(" ")}\""
                }
            }.joinToString(" AND ")
            else -> Regex("\"[^\"]+\"|\\S+").findAll(t).joinToString(" AND ") { "$field:${it.value}" }
        }
        if (cat.isNotBlank()) { val c = catClause(cat.split(",")); q = if (q.isNotEmpty()) "($q) AND $c" else c }
        if (!topicKws.isNullOrEmpty()) { val tc = topicClause(topicKws); q = if (q.isNotEmpty()) "($q) AND $tc" else tc }
        return q
    }

    fun searchArxiv(text: String, field: String, cat: String, sort: String, start: Int, n: Int, topicKws: List<String>?): Pair<List<Paper>, Int> {
        val url = if (field == "id") {
            val ids = text.split(",").map { it.trim().replace(Regex("^(arxiv:|https?://arxiv.org/(abs|pdf)/)", RegexOption.IGNORE_CASE), "") }
                .filter { it.isNotEmpty() }.joinToString(",")
            "https://export.arxiv.org/api/query?id_list=${enc(ids)}&start=$start&max_results=$n"
        } else {
            val s = if (sort == "newest") "submittedDate" else "relevance"
            "https://export.arxiv.org/api/query?search_query=${enc(buildQuery(text, field, cat, topicKws))}&sortBy=$s&sortOrder=descending&start=$start&max_results=$n"
        }
        return parseArxiv(get(url, timeoutMs = 60_000))
    }

    // ------------------------------------------------------------------ extras
    fun citation(p: Paper, fmt: String): String {
        val accept = if (fmt == "bibtex") "application/x-bibtex" else "application/x-research-info-systems"
        if (p.doi.isNotEmpty()) {
            return runCatching { get("https://doi.org/${p.doi}", accept = accept, timeoutMs = 30_000).trim() }.getOrElse {
                get("https://api.crossref.org/works/${enc(p.doi)}/transform/$accept", timeoutMs = 30_000).trim()
            }
        }
        if (fmt == "bibtex" && p.kind == "arxiv") return get("https://arxiv.org/bibtex/" + p.id.removePrefix("arxiv:")).trim()
        return (listOf("TY  - JOUR") + p.authors.map { "AU  - $it" } + listOf(
            "TI  - ${p.title}", "PY  - ${p.date.take(4)}", "DA  - ${p.date}", "JO  - ${p.source}",
            "UR  - ${p.url}", "N2  - ${p.abstract}", "ER  - ",
        )).joinToString("\n")
    }

    fun fullAbstract(p: Paper): String = when {
        p.kind == "arxiv" -> searchArxiv(p.id.removePrefix("arxiv:"), "id", "", "relevance", 0, 1, null).first.firstOrNull()?.abstract ?: ""
        p.doi.isNotEmpty() -> clean(JSONObject(get("https://api.crossref.org/works/" + enc(p.doi), timeoutMs = 30_000))
            .getJSONObject("message").optString("abstract")).removePrefix("Abstract").trim()
        else -> ""
    }

    fun pdfLink(p: Paper): String {
        if (p.pdf.isNotEmpty()) return p.pdf
        if (p.doi.isEmpty()) return ""
        val links = JSONObject(get("https://api.crossref.org/works/" + enc(p.doi), timeoutMs = 30_000)).getJSONObject("message").optJSONArray("link")
            ?: return ""
        for (i in 0 until links.length()) {
            val l = links.getJSONObject(i)
            if (l.optString("content-type").contains("pdf") && l.optString("URL").isNotEmpty()) return l.optString("URL")
        }
        return ""
    }

    // ------------------------------------------------------------------ the daily fetch
    /** Fetch everything (arXiv + journals + news in parallel) and merge into the feed.
     *  onPartial gets the growing feed after each source, so the list fills in while it loads.
     *  First run: only 2 days of arXiv, so the first screen appears fast. */
    suspend fun refresh(conf: Config, old: FeedData, onPartial: (FeedData) -> Unit = {}): Pair<FeedData, List<Paper>> = coroutineScope {
        val today = LocalDate.now()
        val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
        val fresh = java.util.Collections.synchronizedList(mutableListOf<Paper>())
        val lock = Mutex()
        var arxivOk = false
        suspend fun publish() = lock.withLock { onPartial(merge(conf, old, fresh.toList(), errors.toMap(), old.arxivSince)) }
        val gate = Semaphore(8)
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()
        jobs += async(Dispatchers.IO) {
            runCatching {
                val since = if (old.arxivSince.isNotEmpty()) LocalDate.parse(old.arxivSince).minusDays(2) else today.minusDays(2)
                fresh += fetchArxiv(conf.arxivCategories, since.toString())
                arxivOk = true
            }.onFailure { errors["arXiv"] = it.message ?: "error" }
            publish()
        }
        for ((name, spec) in conf.journals) jobs += async(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    val keep = if (spec.keep > 0) spec.keep.toLong() else KEEP_DAYS
                    val got = if (spec.url == "crossref") fetchCrossref(name, spec.issn, today.minusDays(keep).toString()) else fetchRss(name, spec.url)
                    fresh += got.map { it.copy(filtered = spec.onlyMyTopics, oa = spec.oa || it.oa, keep = if (spec.keep > 0) spec.keep else 0) }
                }.onFailure { errors[name] = (it.message ?: "error").take(120) }
            }
        }
        val jsince = today.minusDays(KEEP_DAYS).toString()
        for ((name, pair) in conf.news) jobs += async(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    fresh += fetchRss(name, pair.first).filter { it.date >= jsince }.take(60)
                        .map { it.copy(kind = "news", group = pair.second, abstract = it.abstract.take(1500)) }
                }.onFailure { errors[name] = (it.message ?: "error").take(120) }
            }
            publish()
        }
        // publish journal results in batches, not after every single one
        launch { while (jobs.any { it.isActive }) { kotlinx.coroutines.delay(2500); publish() } }
        jobs.awaitAll()
        val feed = merge(conf, old, fresh.toList(), errors.toMap(), if (arxivOk) today.toString() else old.arxivSince)
        val oldIds = old.items.map { it.id }.toSet()
        val newHits = feed.items.filter { it.topics.isNotEmpty() && it.id !in oldIds }
        feed to (if (oldIds.isEmpty()) emptyList() else newHits)
    }

    fun merge(conf: Config, old: FeedData, fresh: List<Paper>, errors: Map<String, String>, arxivSince: String): FeedData {
        val compiled = compileTopics(conf.topics)
        val today = LocalDate.now()
        val byId = LinkedHashMap<String, Paper>()
        old.items.forEach { byId[it.id] = it }
        val jdoi = HashMap<String, String>()
        byId.values.filter { it.doi.isNotEmpty() && it.kind == "journal" }.forEach { jdoi[it.doi.lowercase()] = it.id }
        for (it0 in fresh) {
            val tags = if (it0.kind == "news") emptyList() else match(it0.title + " " + it0.abstract, compiled)
            if (it0.filtered && tags.isEmpty()) continue
            val d = it0.doi.lowercase()
            if (it0.kind == "journal" && d.isNotEmpty()) {
                jdoi[d]?.let { prevId -> if (prevId != it0.id) byId.remove(prevId) }
                jdoi[d] = it0.id
            }
            val prev = byId[it0.id]
            byId[it0.id] = it0.copy(topics = tags, firstSeen = prev?.firstSeen?.ifEmpty { null } ?: today.toString(),
                image = it0.image.ifEmpty { prev?.image ?: "" })
        }
        val cutoff = today.minusDays(KEEP_DAYS).toString()
        val items = byId.values.filter { p ->
            val limit = if (p.keep > 0) today.minusDays(p.keep.toLong()).toString() else cutoff
            (p.date.ifEmpty { p.firstSeen }) >= limit
        }.sortedWith(compareByDescending<Paper> { it.date }.thenByDescending { it.firstSeen })
        return FeedData(
            updated = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            items = items, errors = errors, arxivSince = arxivSince,
        )
    }
}
