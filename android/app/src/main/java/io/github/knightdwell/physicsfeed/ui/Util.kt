package io.github.knightdwell.physicsfeed.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.knightdwell.physicsfeed.data.Paper
import java.io.File
import java.net.URLEncoder

private val GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
    "eta" to "η", "theta" to "θ", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω", "hbar" to "ħ", "infty" to "∞", "pm" to "±", "times" to "×", "approx" to "≈", "sim" to "~",
    "leq" to "≤", "geq" to "≥", "le" to "≤", "ge" to "≥", "to" to "→", "rightarrow" to "→", "langle" to "⟨",
    "rangle" to "⟩", "uparrow" to "↑", "downarrow" to "↓", "circ" to "°", "prime" to "′", "AA" to "Å", "ell" to "ℓ",
    "cdot" to "·", "partial" to "∂", "nabla" to "∇", "neq" to "≠", "propto" to "∝",
)
private const val SUB_FROM = "0123456789+-=()aeoxijklmnpstr"
private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓᵢⱼₖₗₘₙₚₛₜᵣ"
private const val SUP_FROM = "0123456789+-=()nix"
private const val SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣ"

private fun tr(s: String, from: String, to: String): String? {
    val sb = StringBuilder()
    for (c in s) { val i = from.indexOf(c); if (i < 0) return null; sb.append(to[i]) }
    return sb.toString()
}

/** Readable text from the bits of LaTeX in paper titles/abstracts (CrPS$_4$ -> CrPS₄). */
fun tex2text(s0: String): String {
    if ('\\' !in s0 && '$' !in s0 && "_{" !in s0 && "^{" !in s0) return s0
    var s = s0.replace(Regex("\\\\(?:mathrm|mathit|mathbf|text|textrm|textit|mathcal|rm|bf|it|operatorname|boldsymbol)\\s*\\{([^{}]*)\\}"), "$1")
    s = s.replace(Regex("\\\\([A-Za-z]+)")) { m -> GREEK[m.groupValues[1]] ?: m.value }
    s = s.replace(Regex("_\\{([^{}]*)\\}|_([0-9A-Za-z])")) { m ->
        val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }; tr(inner, SUB_FROM, SUB_TO) ?: "_$inner"
    }
    s = s.replace(Regex("\\^\\{([^{}]*)\\}|\\^([0-9A-Za-z+\\-])")) { m ->
        val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }; tr(inner, SUP_FROM, SUP_TO) ?: "^$inner"
    }
    return s.replace("$", "").replace("{", "").replace("}", "").replace("\\", "").replace(Regex("\\s+"), " ").trim()
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

fun copy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

/** Android's own share sheet: WhatsApp, Telegram, Gmail, Slack, Nearby Share… whatever is installed. */
fun sharePaper(ctx: Context, p: Paper) {
    val who = p.authors.firstOrNull()?.split(" ")?.lastOrNull()?.let { if (p.authors.size > 1) "$it et al." else it }
    val text = tex2text(p.title) + (who?.let { " — $it" } ?: "") + " (${p.source})\n${p.url}"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, tex2text(p.title))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share paper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareText(ctx: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareFile(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Open a downloaded PDF in the phone's PDF viewer. */
fun openPdf(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(view) }.onFailure { Toast.makeText(ctx, "Install a PDF viewer (e.g. Google Drive) to read PDFs", Toast.LENGTH_LONG).show() }
}

fun scholarUrl(p: Paper) = "https://scholar.google.com/scholar?q=" + URLEncoder.encode("\"" + tex2text(p.title) + "\"", "UTF-8")
