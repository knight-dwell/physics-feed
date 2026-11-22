package io.github.knightdwell.physicsfeed.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Files in the app's private storage: config.json, feed.json, state.json (same formats as the desktop app). */
class Store(private val context: Context) {
    private val dir get() = context.filesDir
    val configFile get() = File(dir, "config.json")
    private val feedFile get() = File(dir, "feed.json")
    private val stateFile get() = File(dir, "state.json")

    fun hasConfig() = configFile.exists()

    fun loadConfig(): Config = runCatching { Config.fromJson(JSONObject(configFile.readText())) }.getOrDefault(Config())

    fun saveConfig(c: Config) = atomicWrite(configFile, c.toJson().toString(2))

    fun importConfig(text: String): Config {
        val c = Config.fromJson(JSONObject(text))   // throws if it isn't a Physics Feed settings file
        saveConfig(c)
        return c
    }

    fun loadFeed(): FeedData = runCatching {
        val o = JSONObject(feedFile.readText())
        val items = o.optJSONArray("items") ?: JSONArray()
        val errs = o.optJSONObject("errors")
        FeedData(
            updated = o.optString("updated"),
            items = (0 until items.length()).map { Paper.fromJson(items.getJSONObject(it)) },
            errors = errs?.keys()?.asSequence()?.associateWith { errs.optString(it) } ?: emptyMap(),
            arxivSince = o.optString("arxiv_since"),
        )
    }.getOrDefault(FeedData())

    fun saveFeed(f: FeedData) {
        val o = JSONObject()
        o.put("updated", f.updated)
        o.put("arxiv_since", f.arxivSince)
        o.put("errors", JSONObject(f.errors))
        o.put("items", JSONArray().apply { f.items.forEach { put(it.toJson()) } })
        atomicWrite(feedFile, o.toString())
    }

    data class State(
        val saved: Map<String, Paper> = emptyMap(),
        val folders: List<String> = emptyList(),
        val theme: String = "system",        // system | dark | light
        val lastOpen: String = "",
        val autoUpdate: Boolean = true,
        val updateChecked: String = "",
        val builtinPdf: Boolean = true,      // read PDFs inside the app (false: hand them to a PDF app)
        val newsCards: Boolean = true,       // swipeable stacked cards in every section (false: the normal list)
    )

    fun loadState(): State = runCatching {
        val o = JSONObject(stateFile.readText())
        val s = o.optJSONObject("saved") ?: JSONObject()
        State(
            saved = s.keys().asSequence().associateWith { Paper.fromJson(s.getJSONObject(it)) },
            folders = o.optJSONArray("folders").strings(),
            theme = o.optString("theme", "system"),
            lastOpen = o.optString("last_open"),
            autoUpdate = o.optBoolean("auto_update", true),
            updateChecked = o.optString("update_checked"),
            builtinPdf = o.optBoolean("builtin_pdf", true),
            newsCards = o.optBoolean("news_cards", true),
        )
    }.getOrDefault(State())

    fun saveState(st: State) {
        val o = JSONObject()
        o.put("saved", JSONObject().apply { st.saved.forEach { (k, v) -> put(k, v.toJson()) } })
        o.put("folders", JSONArray(st.folders))
        o.put("theme", st.theme)
        o.put("last_open", st.lastOpen)
        o.put("auto_update", st.autoUpdate)
        o.put("update_checked", st.updateChecked)
        o.put("builtin_pdf", st.builtinPdf)
        o.put("news_cards", st.newsCards)
        atomicWrite(stateFile, o.toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
