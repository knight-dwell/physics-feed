package io.github.knightdwell.physicsfeed.data

import android.content.Context
import org.json.JSONObject

/** Everything the app can follow (assets/catalog.json, exported from the desktop app's catalog.py). */
class Catalog(context: Context) {
    data class Journal(val name: String, val group: String, val source: String, val oa: Boolean) {
        fun spec(): JournalSpec =
            if (source.startsWith("crossref:")) JournalSpec("crossref", false, source.removePrefix("crossref:"), oa)
            else JournalSpec(source, false, "", oa)
    }

    data class News(val name: String, val group: String, val url: String)
    data class Field(val name: String, val topics: Map<String, List<String>>, val journals: List<String>, val arxiv: List<String>)

    val journals: List<Journal>
    val news: List<News>
    val fields: List<Field>
    val searchJournals: Map<String, String>
    val defaultNews: Map<String, Pair<String, String>>

    init {
        val o = JSONObject(context.assets.open("catalog.json").bufferedReader().readText())
        val j = o.getJSONObject("journals")
        journals = j.keys().asSequence().map { n ->
            val x = j.getJSONObject(n); Journal(n, x.optString("group"), x.optString("source"), x.optBoolean("oa"))
        }.toList()
        val nw = o.getJSONObject("news")
        news = nw.keys().asSequence().map { n -> val x = nw.getJSONObject(n); News(n, x.optString("group"), x.optString("url")) }.toList()
        val f = o.getJSONObject("fields")
        fields = f.keys().asSequence().map { n ->
            val x = f.getJSONObject(n)
            val tps = linkedMapOf<String, List<String>>()
            x.getJSONObject("topics").let { t -> t.keys().forEach { k -> tps[k] = t.optJSONArray(k).strings() } }
            Field(n, tps, x.optJSONArray("journals").strings(), x.optJSONArray("arxiv").strings())
        }.toList()
        val sj = o.getJSONObject("search_journals")
        searchJournals = sj.keys().asSequence().associateWith { sj.optString(it) }
        val dn = o.getJSONObject("default_news")
        defaultNews = dn.keys().asSequence().associateWith { k -> dn.getJSONArray(k).let { it.optString(0) to it.optString(1) } }
    }

    fun journal(name: String) = journals.firstOrNull { it.name == name }
    fun isOpenAccess(name: String) = journal(name)?.oa == true

    /** Broad areas for the search Topic filter: arXiv category + words for journal search. */
    val areas: Map<String, Pair<String, List<String>>> = linkedMapOf(
        "Condensed matter (all)" to ("cond-mat" to listOf("condensed matter", "solid state", "electronic", "lattice", "magnetic", "superconduct", "topological")),
        "Strongly correlated systems" to ("cond-mat.str-el" to listOf("strongly correlated", "correlated electron", "Mott", "Hubbard", "Kondo", "heavy fermion")),
        "Many-body physics" to ("" to listOf("many-body", "many body", "DMFT", "DMRG", "quantum Monte Carlo", "tensor network", "renormalization group")),
        "Topology & topological matter" to ("" to listOf("topological", "Chern", "Berry phase", "edge state", "Weyl", "quantum Hall", "winding number")),
        "Mathematical physics" to ("math-ph" to listOf("mathematical physics", "integrable", "Bethe ansatz", "operator algebra", "spectral theory", "random matrix")),
        "Statistical mechanics" to ("cond-mat.stat-mech" to listOf("statistical mechanics", "phase transition", "non-equilibrium", "stochastic", "critical exponent")),
        "Quantum information" to ("quant-ph" to listOf("entanglement", "qubit", "quantum computing", "quantum error correction")),
        "Soft matter" to ("cond-mat.soft" to listOf("soft matter", "active matter", "colloid", "polymer", "granular")),
        "Optics & photonics" to ("physics.optics" to listOf("photonic", "optical", "laser", "metamaterial", "waveguide")),
        "Superconductivity" to ("cond-mat.supr-con" to listOf("superconduct", "Josephson", "Cooper pair")),
        "Cold atoms" to ("cond-mat.quant-gas" to listOf("ultracold", "optical lattice", "Bose-Einstein condensate")),
    )

    val arxivCategories = listOf(
        "" to "Any category", "cond-mat" to "cond-mat (all)", "cond-mat.str-el" to "Strongly correlated",
        "cond-mat.mes-hall" to "Mesoscale & nano", "cond-mat.supr-con" to "Superconductivity",
        "cond-mat.quant-gas" to "Quantum gases", "cond-mat.dis-nn" to "Disordered systems",
        "cond-mat.mtrl-sci" to "Materials science", "cond-mat.stat-mech" to "Statistical mechanics",
        "cond-mat.soft" to "Soft matter", "quant-ph" to "Quantum physics", "hep-th" to "High energy theory",
        "hep-ph" to "HEP phenomenology", "gr-qc" to "Gravity & cosmology", "math-ph" to "Mathematical physics",
        "physics.optics" to "Optics", "physics.comp-ph" to "Computational physics", "physics.atom-ph" to "Atomic physics",
        "nlin" to "Nonlinear sciences", "cs.LG" to "Machine learning",
    )
}
