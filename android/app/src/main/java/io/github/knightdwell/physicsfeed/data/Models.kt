package io.github.knightdwell.physicsfeed.data

import org.json.JSONArray
import org.json.JSONObject

/** One paper or news item. Same fields as the desktop app's feed.json, so files can move between them. */
data class Paper(
    val id: String,
    val source: String,
    val kind: String,              // arxiv | journal | news
    val title: String,
    val authors: List<String> = emptyList(),
    val abstract: String = "",
    val url: String = "",
    val pdf: String = "",
    val doi: String = "",
    val date: String = "",
    val category: String = "",
    val topics: List<String> = emptyList(),
    val oa: Boolean = false,
    val group: String = "",
    val firstSeen: String = "",
    val filtered: Boolean = false,
    val keep: Int = 0,
    val folders: List<String> = emptyList(),   // only for saved papers
    val flairs: List<String> = emptyList(),
    val image: String = "",                    // picture from the feed (news photo, journal graphic)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("source", source); put("kind", kind); put("title", title)
        put("authors", JSONArray(authors)); put("abstract", abstract); put("url", url)
        if (pdf.isNotEmpty()) put("pdf", pdf)
        if (doi.isNotEmpty()) put("doi", doi)
        put("date", date)
        if (category.isNotEmpty()) put("category", category)
        put("topics", JSONArray(topics))
        if (oa) put("oa", true)
        if (group.isNotEmpty()) put("group", group)
        if (firstSeen.isNotEmpty()) put("first_seen", firstSeen)
        if (keep > 0) put("keep", keep)
        if (folders.isNotEmpty()) put("folders", JSONArray(folders))
        if (flairs.isNotEmpty()) put("flairs", JSONArray(flairs))
        if (image.isNotEmpty()) put("image", image)
    }

    companion object {
        fun fromJson(o: JSONObject) = Paper(
            id = o.optString("id"), source = o.optString("source"), kind = o.optString("kind", "journal"),
            title = o.optString("title"), authors = o.optJSONArray("authors").strings(),
            abstract = o.optString("abstract"), url = o.optString("url"), pdf = o.optString("pdf"),
            doi = o.optString("doi"), date = o.optString("date"), category = o.optString("category"),
            topics = o.optJSONArray("topics").strings(), oa = o.optBoolean("oa"), group = o.optString("group"),
            firstSeen = o.optString("first_seen"), keep = o.optInt("keep"),
            folders = o.optJSONArray("folders").strings(), flairs = o.optJSONArray("flairs").strings(),
            image = o.optString("image"),
        )
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }

/** A journal you follow: RSS url or "crossref" + ISSN. */
data class JournalSpec(
    val url: String, val onlyMyTopics: Boolean = false, val issn: String = "", val oa: Boolean = false, val keep: Int = 0,
) {
    fun toJson() = JSONArray().apply { put(url); put(onlyMyTopics); put(issn); put(oa); if (keep > 0) put(keep) }

    companion object {
        fun fromJson(a: JSONArray) = JournalSpec(
            url = a.optString(0), onlyMyTopics = a.optBoolean(1), issn = a.optString(2),
            oa = a.optBoolean(3), keep = a.optInt(4),
        )
    }
}

/** Settings: the same structure as ~/.config/physics-feed/config.json on the desktop. */
data class Config(
    val topics: Map<String, Map<String, List<String>>> = emptyMap(),
    val arxivCategories: List<String> = emptyList(),
    val journals: Map<String, JournalSpec> = emptyMap(),
    val news: Map<String, Pair<String, String>> = emptyMap(),   // name -> (url, group)
    val quick: List<String> = listOf("arXiv", "PRL", "PRB", "PRX", "Rev. Mod. Phys."),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("_help", "Physics Feed settings (same format on desktop and Android)")
        put("topics", JSONObject().apply {
            topics.forEach { (g, tps) -> put(g, JSONObject().apply { tps.forEach { (t, k) -> put(t, JSONArray(k)) } }) }
        })
        put("arxiv_categories", JSONArray(arxivCategories))
        put("journals", JSONObject().apply { journals.forEach { (n, s) -> put(n, s.toJson()) } })
        put("news", JSONObject().apply { news.forEach { (n, p) -> put(n, JSONArray(listOf(p.first, p.second))) } })
        put("quick", JSONArray(quick))
    }

    companion object {
        fun fromJson(o: JSONObject): Config {
            val topics = linkedMapOf<String, Map<String, List<String>>>()
            o.optJSONObject("topics")?.let { t ->
                t.keys().forEach { g ->
                    val inner = linkedMapOf<String, List<String>>()
                    t.optJSONObject(g)?.let { tp -> tp.keys().forEach { n -> inner[n] = tp.optJSONArray(n).strings() } }
                    topics[g] = inner
                }
            }
            val journals = linkedMapOf<String, JournalSpec>()
            o.optJSONObject("journals")?.let { j -> j.keys().forEach { n -> j.optJSONArray(n)?.let { journals[n] = JournalSpec.fromJson(it) } } }
            val news = linkedMapOf<String, Pair<String, String>>()
            o.optJSONObject("news")?.let { j ->
                j.keys().forEach { n -> j.optJSONArray(n)?.let { news[n] = it.optString(0) to it.optString(1, "News") } }
            }
            return Config(
                topics = topics,
                arxivCategories = o.optJSONArray("arxiv_categories").strings(),
                journals = journals, news = news,
                quick = o.optJSONArray("quick")?.strings() ?: listOf("arXiv", "PRL", "PRB", "PRX", "Rev. Mod. Phys."),
            )
        }
    }
}

data class FeedData(
    val updated: String = "",
    val items: List<Paper> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val arxivSince: String = "",
)
