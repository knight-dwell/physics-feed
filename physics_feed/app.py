#!/usr/bin/env python3
"""Physics Feed: a reading room for new papers (arXiv + journals) and science news, sorted into your topics.

Sidebar: Search papers / For you / New today / Saved (folders, your flairs) / Open access, your topic
groups, journals, news. Click a paper: full abstract on the right (Read PDF, Journal page, Cite, Share,
Scholar). Settings: ~/.config/physics-feed/config.json (My topics, Journals & news buttons).
Saved papers, folders and flairs: ~/.local/share/physics-feed/state.json.
"""
import datetime as dt
import json
import os
import subprocess
import sys
import threading

import gi  # noqa: E402
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, Gio, GLib, Gtk, Pango  # noqa: E402

from . import feed as RF  # noqa: E402
from . import catalog as FC  # noqa: E402
from . import __version__  # noqa: E402
from . import updater  # noqa: E402
import urllib.parse  # noqa: E402
import urllib.request  # noqa: E402

try:   # PDF preview (Evince widget) and web preview (WebKit) are optional
    import gi
    gi.require_version("EvinceView", "3.0")
    gi.require_version("EvinceDocument", "3.0")
    from gi.repository import EvinceDocument, EvinceView  # noqa: E402
    EvinceDocument.init()
except Exception:
    EvinceView = None
try:
    try:
        gi.require_version("WebKit2", "4.1")
    except ValueError:
        gi.require_version("WebKit2", "4.0")
    from gi.repository import WebKit2  # noqa: E402
except Exception:
    WebKit2 = None


SEARCH_FIELDS = [("all", "All fields"), ("ti", "Title"), ("au", "Author"), ("abs", "Abstract"), ("id", "arXiv ID"),
                 ("adv", "Advanced")]
SEARCH_CATS = [("", "Any category"), ("cond-mat", "cond-mat (all)"), ("cond-mat.str-el", "Strongly correlated"),
               ("cond-mat.mes-hall", "Mesoscale & nano"), ("cond-mat.supr-con", "Superconductivity"),
               ("cond-mat.quant-gas", "Quantum gases"), ("cond-mat.dis-nn", "Disordered systems"),
               ("cond-mat.mtrl-sci", "Materials science"), ("cond-mat.stat-mech", "Statistical mechanics"),
               ("quant-ph", "Quantum physics"), ("hep-th", "High energy theory"), ("physics.optics", "Optics"),
               ("physics.comp-ph", "Computational physics"), ("cond-mat.soft", "Soft matter"),
               ("cond-mat.other", "cond-mat other"), ("math-ph", "Mathematical physics"), ("gr-qc", "Gravity & cosmology"),
               ("hep-ph", "High energy phenomenology"), ("hep-lat", "Lattice field theory"),
               ("nlin", "Nonlinear sciences"), ("physics.atom-ph", "Atomic physics"),
               ("physics.app-ph", "Applied physics"), ("physics.bio-ph", "Biological physics"),
               ("physics.flu-dyn", "Fluid dynamics"), ("cs.LG", "Machine learning (cs.LG)"),
               ("physics", "physics (all)")]

# broad areas for the search "Topic" filter: an arXiv category + words for journal search
AREAS = {
    "Condensed matter (all)": ("cond-mat", ["condensed matter", "solid state", "electronic", "lattice", "phonon",
                                            "magnetic", "superconduct", "topological"]),
    "Strongly correlated systems": ("cond-mat.str-el", ["strongly correlated", "correlated electron", "Mott",
                                                        "Hubbard", "Kondo", "heavy fermion"]),
    "Many-body physics": ("", ["many-body", "many body", "DMFT", "DMRG", "quantum Monte Carlo", "tensor network",
                               "Green's function", "renormalization group"]),
    "Topology & topological matter": ("", ["topological", "Chern", "Berry phase", "edge state", "Weyl",
                                           "quantum Hall", "winding number"]),
    "Mathematical physics": ("math-ph", ["mathematical physics", "integrable", "Bethe ansatz", "operator algebra",
                                         "spectral theory", "conformal field theory", "random matrix"]),
    "Statistical mechanics": ("cond-mat.stat-mech", ["statistical mechanics", "phase transition", "non-equilibrium",
                                                     "stochastic", "critical exponent", "Ising"]),
    "Quantum information": ("quant-ph", ["entanglement", "qubit", "quantum computing", "quantum error correction"]),
    "Soft matter": ("cond-mat.soft", ["soft matter", "active matter", "colloid", "polymer", "granular"]),
    "Optics & photonics": ("physics.optics", ["photonic", "optical", "laser", "metamaterial", "waveguide"]),
    "Superconductivity": ("cond-mat.supr-con", ["superconduct", "Josephson", "Cooper pair"]),
    "Cold atoms": ("cond-mat.quant-gas", ["ultracold", "optical lattice", "Bose-Einstein condensate"]),
}
SEARCH_HINT = {"all": "e.g.  slave boson Kondo   or   \"topological photonics\"",
               "ti": "words in the title, e.g.  extended SSH negative coupling",
               "au": "e.g.  Piers Coleman   or   P. Coleman, A. Georges",
               "abs": "words in the abstract",
               "id": "e.g.  2511.01234   (several: comma separated)",
               "adv": "arXiv syntax, e.g.  au:\"Piers Coleman\" AND ti:Kondo ANDNOT ti:review"}

PDF_DIR = os.path.join(RF.CACHE_DIR, "pdf")

CACHE = RF.CACHE
CONF = RF.CONF
STATE_DIR = os.path.join(os.environ.get("XDG_DATA_HOME", os.path.expanduser("~/.local/share")), "physics-feed")
STATE = os.path.join(STATE_DIR, "state.json")
PAGE = 60
FIRST = 12    # cards drawn at once; the rest follow in batches while you read
BATCH = 12

GROUP_ICON = {"Condensed matter": "\U000f0768", "Methods": "\U000f0493", "Topology": "\U000f0b7e",
              "Photonics": "\U000f0335"}
SOURCE_CLASS = {"arXiv": "src-arxiv", "PRL": "src-aps", "PRB": "src-aps", "PRX": "src-aps",
                "PR Research": "src-aps", "Advanced Materials": "src-wiley",
                "Adv. Theory & Simulations": "src-wiley"}

CSS = """
window, .main { background: $surface; color: $on_surface; font-family: "$font_family"; }
.sidebar, .sidebar row, .main, .preview, list, row, dialog, .topics-dialog { color: $on_surface; }
button.pill, button.iconbtn, button.star, togglebutton.pill, .pillcombo button { background-image: none;
    text-shadow: none; -gtk-icon-shadow: none; }
button.pill label, button.iconbtn label, .pillcombo button label { color: inherit; }
togglebutton.pill:checked, button.pill:checked { background: $primary; color: $on_primary; }
entry, entry.search { color: $on_surface; caret-color: $primary; }
entry image { color: $on_surface_variant; }
tooltip, tooltip * { background: $surface_container_highest; color: $on_surface; }
popover, menu, .menu, combobox window { background: $surface_container; color: $on_surface; }
menuitem:hover { background: $primary_container; color: $on_primary_container; }
.sidebar { background: $surface_container; padding: 10px 8px; }
.sidebar list, .sidebar row { background: transparent; }
.sidebar row { border-radius: 999px; padding: 6px 12px; margin: 1px 0; }
.sidebar row:hover { background: $surface_container_high; }
.sidebar row:selected { background: $primary_container; color: $on_primary_container; }
.side-head { color: $outline; font-size: 8.5pt; font-weight: bold; padding: 12px 12px 4px 12px; }
.count { color: $on_surface_variant; font-size: 8.5pt; }
row:selected .count { color: $on_primary_container; }
.apptitle { font-size: 16pt; font-weight: bold; color: $primary; }
.subtitle { color: $on_surface_variant; font-size: 9pt; }
.viewtitle { font-size: 13pt; font-weight: bold; }
entry.search { background: $surface_container_high; color: $on_surface; border: none; border-radius: 999px;
               padding: 6px 14px; min-height: 22px; box-shadow: none; }
.pill { background: $surface_container_high; color: $on_surface; border: none; border-radius: 999px;
        padding: 5px 14px; box-shadow: none; }
.pill:disabled { color: $outline; }
.pill:hover { background: $surface_container_highest; }
.pill.primary { background: $primary; color: $on_primary; }
.card { background: $surface_container; border-radius: ${radius}px; padding: 14px 16px; }
.card:hover { background: $surface_container_high; }
.card.new { border-left: 3px solid $primary; }
.card.selected { background: $surface_container_highest; box-shadow: inset 0 0 0 1px $primary; }
.thumb { border-radius: 12px; }
.preview { background: $surface_container; border-radius: 18px; border: 1px solid $outline_variant;
           padding: 4px; margin: 0 0 0 4px; }
.ptools { margin: 2px 0 4px 0; }
.ptools button { background: transparent; border: 1px solid $outline_variant; border-radius: 999px;
                 padding: 2px 9px; min-height: 0; min-width: 0; box-shadow: none; background-image: none; }
.ptools button:hover { background: $surface_container_highest; }
.ptools button.primary { background: $primary_container; color: $on_primary_container; border-color: transparent; }
.oa { background: #1f4a3a; color: #a8d5a2; }
.folderchip { background: #4a3a1f; color: #f6c177; }
.flairchip { background: #3d2a4a; color: #debcdf; }
.quick { padding: 4px 10px; font-weight: bold; }
.help-text { font-size: 10.5pt; }
.flair { padding: 4px 6px; }
.flair-kw { color: $outline; font-size: 8.5pt; }
.searchbar { background: $surface_container; border-radius: ${radius}px; padding: 10px; }
.pillcombo button { background: $surface_container_high; color: $on_surface; border: none; border-radius: 999px;
                    padding: 3px 12px; box-shadow: none; }
.topics-dialog, .topics-dialog box { background: $surface; color: $on_surface; }
.topics-dialog entry { background: $surface_container_high; color: $on_surface; border: 1px solid $outline_variant;
                       border-radius: 10px; padding: 4px 8px; box-shadow: none; }
.topics-dialog entry:focus { border-color: $primary; }
.topics-dialog combobox button { background: $surface_container_high; border: none; box-shadow: none; }
.abs-title { font-size: 14pt; font-weight: bold; color: $primary; }
.abs-text { font-size: 10.5pt; color: $on_surface; }
paned > separator { background: transparent; min-width: 8px; }
.ctitle { font-size: 11pt; font-weight: bold; color: $on_surface; }
.cauth { color: $on_surface_variant; font-size: 9pt; }
.cabs { color: $outline; font-size: 9pt; }
.chip { border-radius: 999px; padding: 1px 9px; font-size: 8pt; font-weight: bold; }
.src { background: $surface_container_highest; color: $on_surface; }
.src-arxiv { background: #5c2b2b; color: #ffb4ab; }
.src-aps { background: #22385e; color: #adc6ff; }
.src-nature { background: #1f4a3a; color: #a8d5a2; }
.src-wiley { background: #4a3a1f; color: #f6c177; }
.topic { background: $primary_container; color: $on_primary_container; }
.date { color: $outline; font-size: 8.5pt; }
.star { background: transparent; border: none; box-shadow: none; color: $outline; font-size: 13pt; padding: 0 4px; }
.star.on { color: #f6c177; }
.iconbtn { background: transparent; border: none; box-shadow: none; color: $on_surface_variant; padding: 2px 8px;
           border-radius: 999px; }
.iconbtn:hover { background: $surface_container_highest; color: $primary; }
.empty { color: $outline; font-size: 11pt; }
.warn { color: $error; font-size: 8.5pt; }
scrollbar slider { background: $outline_variant; border-radius: 99px; min-width: 6px; }
"""


DARK = {"primary": "#adc6ff", "on_primary": "#102f60", "primary_container": "#2b4678",
        "on_primary_container": "#d8e2ff", "surface": "#111318", "surface_container": "#1e1f25",
        "surface_container_high": "#282a2f", "surface_container_highest": "#33353a", "on_surface": "#e2e2e9",
        "on_surface_variant": "#c4c6d0", "outline": "#8e9099", "outline_variant": "#44474f", "error": "#ffb4ab",
        "radius": 12,
        "font_family": "JetBrainsMono Nerd Font\", \"JetBrains Mono\", \"Symbols Nerd Font\", \"Symbols Nerd Font Mono\", "
                       "\"Cantarell\", \"Noto Sans\", \"sans-serif"}
OPTIONAL_PALETTE = os.path.expanduser("~/.config/theme/generated/palette.json")   # a desktop theme may provide colours


def palette():
    p = dict(DARK)
    try:
        with open(OPTIONAL_PALETTE) as fh:
            extra = json.load(fh)
        p.update({k: v for k, v in extra.items() if isinstance(v, (str, int, float)) and k != "font_family"})
    except (OSError, ValueError):
        pass
    return p


LIGHT = {"primary": "#415f91", "on_primary": "#ffffff", "primary_container": "#d6e3ff",
         "on_primary_container": "#001b3e", "surface": "#f9f9ff", "surface_container": "#ededf4",
         "surface_container_high": "#e7e8ee", "surface_container_highest": "#e1e2e8", "on_surface": "#191c20",
         "on_surface_variant": "#44474e", "outline": "#74777f", "outline_variant": "#c4c6d0", "error": "#ba1a1a"}
LIGHT_EXTRA = """
.src-arxiv { background: #ffdad6; color: #8c1d18; }
.src-aps { background: #d6e3ff; color: #1f3a66; }
.src-nature { background: #cdeccb; color: #1f4d2b; }
.src-wiley { background: #ffe0b2; color: #6b4300; }
.oa { background: #cdeccb; color: #1f4d2b; }
.folderchip { background: #ffe0b2; color: #6b4300; }
.flairchip { background: #f3dcf6; color: #5a2d63; }
.star.on { color: #b07800; }
"""


HELP = """<b>Physics Feed</b> collects new papers from arXiv and journals every day (8:30 and 20:30) and sorts them
into <b>your topics</b>. News feeds have their own section.

<b>Sidebar</b>
• <b>Search papers</b> – search all of arXiv, all journals (Crossref) or Google Scholar, by words, author or ID/DOI.
• <b>For you</b> – papers matching any of your topics.  <b>New today</b> – fetched today.
• <b>Saved</b> – papers you starred (☆ on a card).  <b>Open access</b> – free-to-read papers.
• Topic groups, then <b>Journals</b>, then <b>News</b> (ML &amp; AI, physics news, Indian science, DST/PIB).

<b>Reading</b>
• Click a paper → its full abstract on the right.  Click it again → the panel closes.
• <b>Read PDF</b> (arXiv) / <b>Read here</b> (open access) opens the PDF in the panel; <b>Journal page</b> shows the
  publisher's page (your institute's access works there).  Ctrl+click a paper → browser.
• <b>Cite</b> → BibTeX or RIS: copy, save, or add to your .bib.  <b>Scholar</b> → the paper on Google Scholar.
• <b>Share</b> → copy the link, or send it by email, WhatsApp, Telegram, X, Bluesky or LinkedIn.
• <b>Save</b> → keep it; put it in <b>folders</b> (Thesis refs, To read…) and give it your own <b>#flair</b> tags.
  Folders and flairs appear under <b>Saved</b> in the sidebar.
• PDF tools: ← back to abstract, browser, save PDF, open in PDF viewer, dark pages, zoom −/+.

<b>Make it yours</b>
• <b>My topics</b> → <b>＋ Add a field…</b> (theory, experiment, soft matter, stat mech, AMO, quantum info, photonics,
  ML, maths-phys…) and tick its tags. Edit keywords any time: comma separated; DMFT-style short words match whole
  words; a&amp;b means both must appear.
• <b>Journals &amp; news</b> → tick what to follow, or <b>＋ Add your own</b> with a feed link or the journal's ISSN.
• Quick buttons (arXiv, PRL, PRB, PRX, RMP) jump straight to that journal.
• ☀/☾ switches light and dark.  ☰ hides the topic list.  ⟳ fetches right now.

<b>Updates</b>  the ⇪ button: check for a new version; the switch keeps Physics Feed updated by itself.

<b>Terminal</b>  <tt>pfeed</tt> opens the app · <tt>pfeed --refresh</tt> fetches now · <tt>pfeed --help</tt>

<b>Keys</b>  Ctrl+F search the list · Esc close the panel / clear the search"""


def load_json(p, default):
    try:
        with open(p) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


GREEK = {"alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "epsilon": "ε", "varepsilon": "ε", "zeta": "ζ",
         "eta": "η", "theta": "θ", "kappa": "κ", "lambda": "λ", "mu": "μ", "nu": "ν", "xi": "ξ", "pi": "π",
         "rho": "ρ", "sigma": "σ", "tau": "τ", "phi": "φ", "varphi": "φ", "chi": "χ", "psi": "ψ", "omega": "ω",
         "Gamma": "Γ", "Delta": "Δ", "Theta": "Θ", "Lambda": "Λ", "Sigma": "Σ", "Phi": "Φ", "Psi": "Ψ",
         "Omega": "Ω", "hbar": "ħ", "infty": "∞", "pm": "±", "times": "×", "approx": "≈", "sim": "~",
         "leq": "≤", "geq": "≥", "le": "≤", "ge": "≥", "to": "→", "rightarrow": "→", "langle": "⟨",
         "rangle": "⟩", "uparrow": "↑", "downarrow": "↓", "circ": "°", "prime": "′", "AA": "Å", "ell": "ℓ",
         "cdot": "·", "partial": "∂", "nabla": "∇", "neq": "≠", "propto": "∝"}
SUB = str.maketrans("0123456789+-=()aeoxijklmnpstr", "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓᵢⱼₖₗₘₙₚₛₜᵣ")
SUP = str.maketrans("0123456789+-=()nix", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣ")


def tex2text(s):
    """Readable text from the bits of LaTeX found in paper titles/abstracts."""
    import re
    if "\\" not in s and "$" not in s and "_{" not in s and "^{" not in s:
        return s
    s = re.sub(r"\\(?:mathrm|mathit|mathbf|text|textrm|textit|mathcal|rm|bf|it|operatorname|boldsymbol)\s*\{([^{}]*)\}", r"\1", s)
    s = re.sub(r"\\(?:mathrm|rm|bf|it)\s+", "", s)
    s = re.sub(r"\\([A-Za-z]+)", lambda m: GREEK.get(m.group(1), m.group(0)), s)
    def rep(m, table):
        inner = m.group(1) if m.group(1) is not None else m.group(2)
        t = inner.translate(table)
        return t if all(ch not in t for ch in inner if ch.isalnum() and ch.translate(table) == ch) else ("_" if table is SUB else "^") + inner
    s = re.sub(r"_\{([^{}]*)\}|_([0-9A-Za-z])", lambda m: rep(m, SUB), s)
    s = re.sub(r"\^\{([^{}]*)\}|\^([0-9A-Za-z+\-])", lambda m: rep(m, SUP), s)
    s = s.replace("$", "").replace("{", "").replace("}", "").replace("\\", "")
    return " ".join(s.split())


def open_uri(uri):
    Gio.AppInfo.launch_default_for_uri(uri, None)


class Feed(Gtk.Window):
    def __init__(self):
        super().__init__(title="Physics Feed")
        self.set_default_size(1240, 820)
        self.set_icon_name("accessories-dictionary")
        self.state = load_json(STATE, {"saved": {}, "last_open": "", "read": []})
        self.prev_open = self.state.get("last_open", "")
        self.data = {"items": []}
        self.view = ("special", "foryou")
        self.query = ""
        self.shown = PAGE

        root = Gtk.Box()
        self.add(root)
        # ---- sidebar
        side_scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        side_scroll.set_size_request(220, -1)
        self.side = Gtk.ListBox()
        self.side.set_header_func(self._side_header)
        self.side.connect("row-selected", self._on_side)
        self.side.set_filter_func(self._side_filter)
        side_scroll.add(self.side)
        side_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        side_box.get_style_context().add_class("sidebar")
        self.side_search = Gtk.SearchEntry(placeholder_text="Find topic, journal… or Enter: search papers")
        self.side_search.get_style_context().add_class("search")
        self.side_search.set_margin_start(8); self.side_search.set_margin_end(8); self.side_search.set_margin_top(10)
        self.side_search.set_tooltip_text("Type to filter this list. Press Enter to search all of arXiv / journals for it.")
        self.side_search.connect("search-changed", lambda *_: self.side.invalidate_filter())
        self.side_search.connect("activate", lambda e: self._side_search_go(e.get_text()))
        side_box.pack_start(self.side_search, False, False, 0)
        side_box.pack_start(side_scroll, True, True, 0)
        self.side_scroll = side_box   # ☰ hides the whole column
        root.pack_start(side_box, False, False, 0)
        # ---- main
        main = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin=18)
        main.get_style_context().add_class("main")
        self.paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, wide_handle=True)
        self.paned.pack1(main, True, False)   # the feed list never goes below its minimum: it shrinks, the panel gives way
        self.main_box = main
        self.preview = self._build_preview()
        self.paned.pack2(self.preview, True, True)
        root.pack_start(self.paned, True, True, 0)
        # row 1: ☰, title, ?          row 2 (wraps when narrow): search + tools
        top = Gtk.Box(spacing=10)
        self.side_btn = self._pill("\U000f035c", lambda *_: self.side_scroll.set_visible(not self.side_scroll.get_visible()))
        self.side_btn.set_tooltip_text("Show/hide the topic list")
        top.pack_start(self.side_btn, False, False, 0)
        tbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        t = Gtk.Label(label="Physics Feed", xalign=0, ellipsize=Pango.EllipsizeMode.END)
        t.get_style_context().add_class("apptitle")
        self.sub = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.sub.get_style_context().add_class("subtitle")
        tbox.pack_start(t, False, False, 0)
        tbox.pack_start(self.sub, False, False, 0)
        top.pack_start(tbox, True, True, 0)
        self.theme_btn = self._pill("", lambda *_: self._toggle_theme())
        top.pack_end(self.theme_btn, False, False, 0)
        help_btn = self._pill("?", lambda *_: self._help())
        help_btn.set_tooltip_text("How to use Physics Feed")
        top.pack_end(help_btn, False, False, 0)
        upd = self._pill("\U000f06b0", lambda *_: self._updates_dialog())
        upd.set_tooltip_text("Updates: check for a new version of Physics Feed, auto-update switch")
        top.pack_end(upd, False, False, 0)
        main.pack_start(top, False, False, 0)
        self.update_bar = Gtk.Button(no_show_all=True)
        self.update_bar.get_style_context().add_class("pill")
        self.update_bar.get_style_context().add_class("primary")
        self.update_bar.connect("clicked", lambda *_: self._updates_dialog())
        main.pack_start(self.update_bar, False, False, 0)

        tools = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=6, column_spacing=6,
                            row_spacing=6, homogeneous=False)
        self.search = Gtk.SearchEntry(placeholder_text="Filter this list")
        self.search.set_tooltip_text("Filter the papers shown (title, authors, abstract). Ctrl+F")
        self.search.get_style_context().add_class("search")
        self.search.set_width_chars(16)
        self.search.connect("search-changed", self._on_search)
        self.refresh_btn = self._pill("\U000f0450", self._refresh, primary=True)
        self.refresh_btn.set_tooltip_text("Fetch new papers now")
        jbtn = self._pill("\U000f0ae4  Journals & news", lambda *_: self._edit_journals())
        jbtn.set_tooltip_text("Choose which journals and news feeds to follow, or add your own by link")
        edit = self._pill("\U000f04f9  My topics", lambda *_: self._edit_topics())
        edit.set_tooltip_text("Your research topics: add fields (＋), pick tags, edit keywords")
        for w in (self.search, self.refresh_btn, jbtn, edit):
            w.set_halign(Gtk.Align.START)
            tools.add(w)
        self.quick_box = Gtk.Box(spacing=4)
        self.quick_box.set_halign(Gtk.Align.START)
        tools.add(self.quick_box)
        self.tools = tools
        main.pack_start(tools, False, False, 0)

        # arXiv search bar (shown in the "Search arXiv" view)
        sb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, no_show_all=True)
        sb.get_style_context().add_class("searchbar")
        r1 = Gtk.Box(spacing=8)
        self.s_entry = Gtk.SearchEntry()
        self.s_entry.get_style_context().add_class("search")
        self.s_entry.connect("activate", lambda *_: self._arxiv_search())
        r1.pack_start(self.s_entry, True, True, 0)
        go = self._pill("\U000f0349  Search", lambda *_: self._arxiv_search(), primary=True)
        r1.pack_start(go, False, False, 0)
        r2 = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=4, column_spacing=6, row_spacing=6)

        def combo(opts, active=0):
            c = Gtk.ComboBoxText()
            for k, v in opts:
                c.append(k, v)
            c.set_active(active)
            c.get_style_context().add_class("pillcombo")
            return c
        self.s_src = combo([("arxiv", "\U000f05da  arXiv"), ("journals", "\U000f0ae4  Journals"),
                            ("scholar", "\U000f0b6e  Google Scholar")])
        self.s_field = combo(SEARCH_FIELDS)
        self.s_field.connect("changed", lambda c: c.get_active_id() and self.s_entry.set_placeholder_text(
            SEARCH_HINT.get(c.get_active_id(), "")))
        self.s_cat = combo(SEARCH_CATS)
        self.s_journal = combo([("", "Any journal")] + [(v, k) for k, v in RF.SEARCH_JOURNALS.items()])
        self.s_sort = combo([("relevance", "Most relevant"), ("newest", "Newest first")])
        self.s_topic = Gtk.ComboBoxText()
        self.s_topic.get_style_context().add_class("pillcombo")
        self.s_topic.set_tooltip_text("Narrow the search to an area or one of your tags (works with an empty search box too)")
        self._fill_topics()
        self.s_src.connect("changed", lambda c: self._on_source())
        for w in (self.s_src, self.s_field, self.s_topic, self.s_cat, self.s_journal, self.s_sort):
            r2.add(w)
        self.s_r2 = r2
        sb.pack_start(r1, False, False, 0)
        sb.pack_start(r2, False, False, 0)
        self.s_entry.set_placeholder_text(SEARCH_HINT["all"])
        self.searchbar = sb
        main.pack_start(sb, False, False, 0)
        self.s_items, self.s_total, self.s_busy, self.s_args = [], 0, False, None
        GLib.idle_add(lambda: self._on_source() or False)

        vt = Gtk.Box(spacing=10)
        self.vtitle = Gtk.Label(xalign=0)
        self.vtitle.get_style_context().add_class("viewtitle")
        self.vcount = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.vcount.get_style_context().add_class("subtitle")
        vt.pack_start(self.vtitle, False, False, 0)
        vt.pack_start(self.vcount, False, False, 0)
        self.warn = Gtk.Label(xalign=1, ellipsize=Pango.EllipsizeMode.END, hexpand=True)
        self.warn.get_style_context().add_class("warn")
        vt.pack_end(self.warn, False, False, 0)
        main.pack_start(vt, False, False, 0)

        self.scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.cards = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.scroll.add(self.cards)
        self.scroll.connect("edge-reached", self._on_edge)
        main.pack_start(self.scroll, True, True, 0)

        self._apply_theme()
        GLib.timeout_add_seconds(4, lambda: self._auto_update_check() or False)
        self.connect("key-press-event", self._on_key)
        self._narrow = None
        self.connect("size-allocate", self._on_size)
        self.connect("destroy", self._on_close)
        self.data = {"items": [], "groups": {}, "journals": []}
        self.sub.set_text("loading…")
        GLib.idle_add(lambda: self._load() or False, priority=GLib.PRIORITY_LOW)   # after the window is on screen
        mon = Gio.File.new_for_path(CACHE).monitor_file(Gio.FileMonitorFlags.NONE, None)
        mon.connect("changed", lambda *a: GLib.timeout_add(400, self._reload_once))
        self._mon = mon
        self._reload_pending = False

    # ---------- helpers
    def _pill(self, label, cb, primary=False):
        b = Gtk.Button(label=label)
        b.get_style_context().add_class("pill")
        if primary:
            b.get_style_context().add_class("primary")
        if cb:
            b.connect("clicked", cb)
        return b

    def _save_state(self):
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(STATE + ".tmp", "w") as fh:
            json.dump(self.state, fh)
        os.replace(STATE + ".tmp", STATE)

    def _reload_once(self):
        self._load()
        return False

    # ---------- data
    def _load(self):
        self.data = load_json(CACHE, {"items": [], "groups": {}, "journals": []})
        items = self.data.get("items", [])
        self.sub.set_text(f"{len(items)} papers · updated {self.data.get('updated', 'never')}")
        errs = self.data.get("errors") or {}
        self.warn.set_text("")   # journals that couldn't be fetched get a web link in the sidebar instead
        self._build_side()
        self._build_quick()
        self._render()

    def _items_for(self, view):
        items = self.data.get("items", [])
        kind, key = view
        today = dt.date.today().isoformat()
        if kind == "special":
            if key == "foryou":
                return [i for i in items if i.get("topics")]
            if key == "today":
                return [i for i in items if i.get("first_seen") == today and i.get("kind") != "news"]
            if key == "news":
                return [i for i in items if i.get("kind") == "news"]
            if key == "oa":
                return [i for i in items if i.get("oa")]
            if key == "saved":
                return list(self.state["saved"].values())
        if kind == "folder":
            return [i for i in self.state["saved"].values() if key in i.get("folders", [])]
        if kind == "flair":
            return [i for i in self.state["saved"].values() if key in i.get("flairs", [])]
            if key == "all":
                return [i for i in items if i.get("kind") != "news"]
            if key == "search":
                return self.s_items
        if kind == "topic":
            return [i for i in items if key in i.get("topics", [])]
        if kind == "group":
            names = set(self.data.get("groups", {}).get(key, []))
            return [i for i in items if names & set(i.get("topics", []))]
        if kind == "source":
            return [i for i in items if i.get("source") == key]
        if kind == "newsgroup":
            return [i for i in items if i.get("kind") == "news" and i.get("group") == key]
        return items

    # ---------- sidebar
    def _row(self, view, icon, label, count, head=None):
        row = Gtk.ListBoxRow()
        row.view, row.head = view, head
        b = Gtk.Box(spacing=10)
        b.pack_start(Gtk.Label(label=icon, width_chars=2), False, False, 0)
        lab = Gtk.Label(label=label, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        b.pack_start(lab, True, True, 0)
        c = Gtk.Label(label=str(count) if count else "")
        c.get_style_context().add_class("count")
        b.pack_end(c, False, False, 0)
        row.add(b)
        return row

    def _side_header(self, row, before):
        if row.head and (before is None or before.head != row.head):
            h = Gtk.Label(label=row.head.upper(), xalign=0)
            h.get_style_context().add_class("side-head")
            row.set_header(h)
        else:
            row.set_header(None)

    def _build_side(self):
        cur = self.view
        for r in self.side.get_children():
            self.side.remove(r)
        rows = [
            self._row(("special", "search"), "\U000f0349", "Search papers", 0),
            self._row(("special", "foryou"), "\U000f04ce", "For you", len(self._items_for(("special", "foryou")))),
            self._row(("special", "today"), "\U000f0b8a", "New today", len(self._items_for(("special", "today")))),

            self._row(("special", "all"), "\U000f0219", "Everything", len(self._items_for(("special", "all")))),
            self._row(("special", "oa"), "\U000f0fc6", "Open access", len(self._items_for(("special", "oa")))),
        ]
        rows.append(self._row(("special", "saved"), "\U000f00c0", "All saved", len(self.state["saved"]), head="Saved"))
        for fo in self.state.setdefault("folders", []):
            rows.append(self._row(("folder", fo), "\U000f024b", fo, len(self._items_for(("folder", fo))), head="Saved"))
        flairs = sorted({f for i in self.state["saved"].values() for f in i.get("flairs", [])})
        for fl in flairs:
            rows.append(self._row(("flair", fl), "#", fl, len(self._items_for(("flair", fl))), head="Saved"))
        for g, topics in self.data.get("groups", {}).items():
            rows.append(self._row(("group", g), GROUP_ICON.get(g, "\U000f0768"), f"All {g.lower()}",
                                  len(self._items_for(("group", g))), head=g))
            for tp in topics:
                rows.append(self._row(("topic", tp), "·", tp, len(self._items_for(("topic", tp))), head=g))
        errs = self.data.get("errors") or {}
        for j in ["arXiv"] + self.data.get("journals", []):
            n = len(self._items_for(("source", j)))
            oa = j in FC.JOURNALS and FC.JOURNALS[j][2]
            if n == 0 and j in errs:   # couldn't fetch it: the row opens the journal's website
                rows.append(self._row(("web", self._journal_site(j)), "\U000f03cc", j + "  ↗", 0, head="Journals"))
            else:
                rows.append(self._row(("source", j), "\U000f0fc6" if oa else ("\U000f0ae4" if j != "arXiv" else "\U000f05da"),
                                      j, n, head="Journals"))
        news = self.data.get("news", {})
        if news:
            rows.append(self._row(("special", "news"), "\U000f0395", "All news", len(self._items_for(("special", "news"))),
                                  head="News"))
            icons = {"ML & AI": "\U000f167a", "Physics news": "\U000f0768", "Indian science": "\U000f0b2d",
                     "DST / PIB": "\U000f0f3e"}
            for g, names in news.items():
                rows.append(self._row(("newsgroup", g), icons.get(g, "\U000f0395"), g,
                                      len(self._items_for(("newsgroup", g))), head="News"))
                for nm in names:
                    rows.append(self._row(("source", nm), "·", nm, len(self._items_for(("source", nm))), head="News"))
        tips = {"search": "Search arXiv, all journals or Google Scholar", "foryou": "Papers matching your topics",
                "today": "Papers fetched today", "saved": "Papers you starred", "all": "Every paper in the feed (6 days)",
                "oa": "Free-to-read papers: they open right here", "news": "All news feeds"}
        for r in rows:
            k, key = r.view
            if k == "special" and key in tips:
                r.set_tooltip_text(tips[key])
            elif k == "group":
                r.set_tooltip_text(f"Papers in any {key} topic")
            elif k == "topic":
                r.set_tooltip_text("Papers whose title/abstract match this topic's keywords (My topics to edit)")
            self.side.add(r)
        self.side.show_all()
        for r in rows:
            if r.view == cur:
                self.side.select_row(r)
                break

    def _side_filter(self, row):
        q = self.side_search.get_text().strip().lower() if hasattr(self, "side_search") else ""
        if not q:
            return True
        lab = [c for c in row.get_child().get_children() if isinstance(c, Gtk.Label)]
        return any(q in (l.get_text() or "").lower() for l in lab)

    def _side_search_go(self, text):
        if not text.strip():
            return
        self._goto(("special", "search"))
        self.s_entry.set_text(text.strip())
        self._arxiv_search()

    def _journal_site(self, name):
        spec = (RF.load_conf().get("journals") or {}).get(name)
        if name == "arXiv":
            return "https://arxiv.org/list/cond-mat/new"
        if spec and spec[0].startswith("http"):
            u = urllib.parse.urlparse(spec[0])
            return f"{u.scheme}://{u.netloc}"
        return "https://www.google.com/search?" + urllib.parse.urlencode({"q": f"{name} journal latest articles"})

    def _on_side(self, lb, row):
        if row is None:
            return
        if row.view[0] == "web":
            open_uri(row.view[1])
            return
        self.view = row.view
        self.shown = PAGE
        searching = self.view == ("special", "search")
        self.searchbar.set_no_show_all(False)
        self.searchbar.set_visible(searching)
        if searching:
            self.searchbar.show_all()
            self._on_source()   # show_all re-shows every filter; hide the ones that don't apply
            self.s_entry.grab_focus()
        self.search.get_parent().set_visible(not searching)
        self._render()

    # ---------- cards
    def _filtered(self):
        items = self._items_for(self.view)
        q = self.query.lower().strip()
        if q:
            items = [i for i in items if q in (i["title"] + " " + " ".join(i.get("authors", [])) + " " +
                                               i.get("abstract", "")).lower()]
        return items

    def _render(self):
        for c in self.cards.get_children():
            c.destroy()
        kind, key = self.view
        names = {"foryou": "For you", "today": "New today", "saved": "Saved", "all": "Everything",
                 "news": "All news", "oa": "Open access",
                 "search": "Search papers"}
        self.vtitle.set_text(names.get(key, key) if kind == "special" else (f"All {key.lower()}" if kind == "group" else key))
        items = self._filtered()
        self.vcount.set_text(f"{len(items)} papers" if key != "search" else
                             (f"{len(items)} of {self.s_total:,} results" if self.s_args else "the whole arXiv database"))
        if not items:
            msg = "Nothing here yet." if key != "saved" else "Star a paper to keep it here."
            if not self.data.get("items"):
                msg = "No papers fetched yet. Press Refresh."
            if key == "search":
                msg = ("Searching arXiv…" if self.s_busy else
                       "No results." if self.s_args else
                       "Search arXiv, or all journals (APS, IOP, Nature, Springer, Wiley, AIP… via Crossref),\n"
                       "by title, author, words or ID / DOI. Google Scholar opens in your browser.")
            e = Gtk.Label(label=msg, margin=40)
            e.get_style_context().add_class("empty")
            self.cards.pack_start(e, False, False, 0)
        self._render_token = getattr(self, "_render_token", 0) + 1
        tok, todo = self._render_token, items[: self.shown]
        first, rest = todo[:FIRST], todo[FIRST:]
        for it in first:
            self.cards.pack_start(self._card(it), False, False, 0)
        self.cards.show_all()
        self.scroll.get_vadjustment().set_value(0) if self.shown == PAGE else None

        def more():   # the rest, a dozen at a time, without freezing the window
            if tok != self._render_token or not rest:
                return False
            for it in rest[:BATCH]:
                c = self._card(it)
                self.cards.pack_start(c, False, False, 0)
                c.show_all()
            del rest[:BATCH]
            return bool(rest)
        if rest:
            GLib.idle_add(more, priority=GLib.PRIORITY_LOW)

    def _card(self, it):
        ev = Gtk.EventBox()
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        card.get_style_context().add_class("card")
        if self.prev_open and it.get("first_seen", "") > self.prev_open[:10]:
            card.get_style_context().add_class("new")
        meta = Gtk.Box(spacing=6)
        src = it.get("source", "")
        chip = Gtk.Label(label=src + (f" · {it['category']}" if it.get("category") else ""),
                         ellipsize=Pango.EllipsizeMode.END, max_width_chars=28)
        chip.get_style_context().add_class("chip")
        chip.get_style_context().add_class(SOURCE_CLASS.get(src, "src-nature" if "Nature" in src or "npj" in src
                                                            or "Scientific" in src else "src"))
        meta.pack_start(chip, False, False, 0)
        if it.get("oa"):
            oac = Gtk.Label(label="\U000f0fc6 open")
            oac.get_style_context().add_class("chip")
            oac.get_style_context().add_class("oa")
            oac.set_tooltip_text("Open access: free to read for everyone, opens here")
            meta.pack_start(oac, False, False, 0)
        tags = None
        mine = self.state["saved"].get(it["id"], {})
        own = [("\U000f024b " + f, "folderchip") for f in mine.get("folders", [])] + \
              [("#" + f, "flairchip") for f in mine.get("flairs", [])]
        if it.get("topics") or own:   # tags wrap onto their own row, so narrow windows still fit
            tags = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=8,
                               column_spacing=4, row_spacing=4, homogeneous=False)
            for tp, cls in own + [(t, "topic") for t in it.get("topics", [])[:5]]:
                c = Gtk.Label(label=tp, ellipsize=Pango.EllipsizeMode.END, max_width_chars=26, halign=Gtk.Align.START)
                c.get_style_context().add_class("chip")
                c.get_style_context().add_class(cls)
                tags.add(c)
        star = Gtk.Button(label="\U000f04ce" if it["id"] in self.state["saved"] else "\U000f04d2")
        star.get_style_context().add_class("star")
        if it["id"] in self.state["saved"]:
            star.get_style_context().add_class("on")
        star.set_tooltip_text("Save")
        star.connect("clicked", self._toggle_save, it)
        meta.pack_end(star, False, False, 0)
        if it.get("pdf"):
            pdf = Gtk.Button(label="PDF")
            pdf.get_style_context().add_class("iconbtn")
            pdf.set_tooltip_text("Read the PDF here")
            pdf.connect("clicked", lambda b: self._select_card(b, it, "pdf"))
            meta.pack_end(pdf, False, False, 0)
        d = Gtk.Label(label=it.get("date", ""))
        d.get_style_context().add_class("date")
        meta.pack_end(d, False, False, 0)
        card.pack_start(meta, False, False, 0)
        if tags:
            card.pack_start(tags, False, False, 0)

        title = Gtk.Label(label=tex2text(it["title"]), xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR)
        title.get_style_context().add_class("ctitle")
        title.set_max_width_chars(40)
        title.set_selectable(False)
        if it.get("image"):
            trow = Gtk.Box(spacing=12)
            trow.pack_start(title, True, True, 0)
            trow.pack_end(self._thumb(it["image"]), False, False, 0)
            card.pack_start(trow, False, False, 0)
        else:
            card.pack_start(title, False, False, 0)
        authors = it.get("authors", [])
        if authors:
            a = Gtk.Label(label=", ".join(authors[:6]) + (" et al." if len(authors) >= 6 else ""), xalign=0,
                          ellipsize=Pango.EllipsizeMode.END)
            a.get_style_context().add_class("cauth")
            card.pack_start(a, False, False, 0)
        if it.get("abstract"):
            ab = Gtk.Label(label=tex2text(it["abstract"]), xalign=0, wrap=True, lines=3,
                           ellipsize=Pango.EllipsizeMode.END, wrap_mode=Pango.WrapMode.WORD_CHAR)
            ab.get_style_context().add_class("cabs")
            ab.set_max_width_chars(40)
            ab.set_tooltip_text(it["abstract"][:1500])
            card.pack_start(ab, False, False, 0)
        ev.add(card)
        ev.connect("button-release-event", self._on_card_click, it, card)
        ev.connect("enter-notify-event", lambda w, e: w.get_window() and w.get_window().set_cursor(
            Gdk.Cursor.new_from_name(w.get_display(), "pointer")))
        return ev

    def _thumb(self, url, size=84):
        """A small rounded picture, downloaded once into ~/.cache/physics-feed/img (in the background)."""
        import hashlib
        img = Gtk.Image()
        img.set_size_request(size, size)
        img.get_style_context().add_class("thumb")
        d = os.path.join(RF.CACHE_DIR, "img")
        path = os.path.join(d, hashlib.sha1(url.encode()).hexdigest()[:20])

        def show():
            try:
                from gi.repository import GdkPixbuf
                pb = GdkPixbuf.Pixbuf.new_from_file(path)
                w, h = pb.get_width(), pb.get_height()
                s = size / min(w, h)
                pb = pb.scale_simple(max(1, int(w * s)), max(1, int(h * s)), GdkPixbuf.InterpType.BILINEAR)
                x, y = (pb.get_width() - size) // 2, (pb.get_height() - size) // 2
                img.set_from_pixbuf(pb.new_subpixbuf(max(0, x), max(0, y), size, size))
            except Exception:
                img.hide()
            return False

        if os.path.exists(path):
            GLib.idle_add(show)
        else:
            def work():
                try:
                    os.makedirs(d, exist_ok=True)
                    data = RF.get(url, timeout=20)
                    with open(path, "wb") as fh:
                        fh.write(data)
                    GLib.idle_add(show)
                except Exception:
                    GLib.idle_add(img.hide)
            threading.Thread(target=work, daemon=True).start()
        return img

    def _toggle_save(self, btn, it):
        if it["id"] in self.state["saved"]:
            del self.state["saved"][it["id"]]
        else:
            self.state["saved"][it["id"]] = dict(it, folders=[], flairs=[])
        self._save_state()
        on = it["id"] in self.state["saved"]
        btn.set_label("\U000f04ce" if on else "\U000f04d2")
        (btn.get_style_context().add_class if on else btn.get_style_context().remove_class)("on")
        self._build_side()
        return True

    # ---------- preview pane (click a card: arXiv PDF, or the journal's page)
    def _build_preview(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin=12, no_show_all=True)
        box.get_style_context().add_class("preview")
        head = Gtk.Box(spacing=6)
        self.p_title = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, max_width_chars=30,
                                 lines=2, ellipsize=Pango.EllipsizeMode.END, width_chars=10)
        self.p_title.get_style_context().add_class("ctitle")
        head.pack_start(self.p_title, True, True, 0)
        close = Gtk.Button(label="\U000f0156")
        close.get_style_context().add_class("iconbtn")
        close.set_tooltip_text("Close preview (Esc)")
        close.connect("clicked", lambda *_: self._close_preview())
        head.pack_end(close, False, False, 0)
        box.pack_start(head, False, False, 0)
        self.p_meta = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.p_meta.get_style_context().add_class("cauth")
        box.pack_start(self.p_meta, False, False, 0)
        bar = Gtk.Box(spacing=4)
        bar.get_style_context().add_class("ptools")
        self.p_open = self._pill("\U000f03cc  Browser", lambda *_: self._p_item and open_uri(self._p_item["url"]))
        self.p_open.get_style_context().add_class("primary")
        self.p_dl = self._pill("\U000f01da", lambda *_: self._save_pdf())
        self.p_ext = self._pill("\U000f0219", lambda *_: self._p_pdf and open_uri("file://" + self._p_pdf))
        self.p_inv = Gtk.ToggleButton(label="\U000f0594")
        self.p_inv.get_style_context().add_class("pill")
        self.p_inv.connect("toggled", lambda b: self._model and self._model.set_inverted_colors(b.get_active()))
        self.p_zin = self._pill("+", lambda *_: self._zoom(1.2))
        self.p_zout = self._pill("−", lambda *_: self._zoom(1 / 1.2))
        self.p_back = self._pill("\U000f004d", lambda *_: self._p_item and self._show_preview(self._p_item, "abstract"))
        self.p_back.set_tooltip_text("Back to the abstract")
        bar.pack_start(self.p_back, False, False, 0)
        self.p_cite = self._cite_button("\U000f0757")
        self.p_cite.set_tooltip_text("Citation: BibTeX / RIS")
        tips = ("Open in browser", "Save PDF to ~/Downloads/papers", "Open in the PDF viewer", "Dark pages",
                "Zoom out", "Zoom in")
        for w, tip in zip((self.p_open, self.p_dl, self.p_ext, self.p_inv, self.p_zout, self.p_zin), tips):
            w.set_tooltip_text(tip)
            bar.pack_start(w, False, False, 0)
        bar.pack_start(self.p_cite, False, False, 0)
        self.p_share = self._share_button("\U000f0497")
        self.p_share.set_tooltip_text("Share this paper")
        bar.pack_start(self.p_share, False, False, 0)
        box.pack_start(bar, False, False, 0)
        self.p_stack = Gtk.Stack(vexpand=True)
        self.p_status = Gtk.Label()
        self.p_status.get_style_context().add_class("empty")
        self.p_stack.add_named(self.p_status, "status")
        abs_sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        ab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin=6)
        self.a_title = Gtk.Label(xalign=0, wrap=True, selectable=True, wrap_mode=Pango.WrapMode.WORD_CHAR,
                                 max_width_chars=40, width_chars=10)
        self.a_title.get_style_context().add_class("abs-title")
        self.a_auth = Gtk.Label(xalign=0, wrap=True, selectable=True, max_width_chars=40, width_chars=10)
        self.a_auth.get_style_context().add_class("cauth")
        self.a_chips = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=6, row_spacing=4,
                                   column_spacing=4)
        self.a_text = Gtk.Label(xalign=0, wrap=True, selectable=True, wrap_mode=Pango.WrapMode.WORD_CHAR,
                                max_width_chars=40, width_chars=10)
        self.a_text.get_style_context().add_class("abs-text")
        acts = Gtk.Box(spacing=8)
        self.a_pdf = self._pill("\U000f0226  Read PDF", lambda *_: self._p_item and self._show_preview(self._p_item, "pdf"),
                                primary=True)
        self.a_web = self._pill("\U000f059f  Journal page", lambda *_: self._p_item and self._show_preview(self._p_item, "web"),
                                primary=True)
        self.a_link = self._pill("\U000f03cc  Open in browser", lambda *_: self._p_item and open_uri(self._p_item["url"]))
        self.a_cite = self._cite_button("\U000f0757  Cite")
        self.a_scholar = self._pill("\U000f0b6e  Scholar", lambda *_: self._p_item and self._scholar(self._p_item))
        self.a_scholar.set_tooltip_text("Find this paper on Google Scholar (citations, versions, related)")
        acts2 = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=6, column_spacing=6,
                            row_spacing=6, homogeneous=False)
        self.a_save = Gtk.MenuButton(label="\U000f00c0  Save")
        self.a_save.get_style_context().add_class("pill")
        self.a_save.set_tooltip_text("Save this paper, put it in folders, add your own flair tags")
        self.a_save.connect("toggled", lambda b: b.get_active() and self._fill_save_menu(b))
        self.a_share = self._share_button("\U000f0497  Share")
        for w in (self.a_pdf, self.a_web, self.a_link, self.a_cite, self.a_save, self.a_share, self.a_scholar):
            w.set_halign(Gtk.Align.START)
            acts2.add(w)
        acts = acts2
        for w in (self.a_title, self.a_auth, self.a_chips, acts, self.a_text):
            ab.pack_start(w, False, False, 0)
        abs_sw.add(ab)
        self.p_stack.add_named(abs_sw, "abstract")
        self._ev_view = self._web = None   # made the first time a PDF / page is shown (they are slow to create)
        box.pack_start(self.p_stack, True, True, 0)
        self._p_item = self._p_pdf = self._model = None
        self._p_token = 0
        return box

    @property
    def ev_view(self):
        if self._ev_view is None and EvinceView:
            self._ev_view = EvinceView.View()
            sw = Gtk.ScrolledWindow()
            sw.add(self._ev_view)
            sw.show_all()
            self.p_stack.add_named(sw, "pdf")
        return self._ev_view

    @property
    def web(self):
        if self._web is None and WebKit2:
            self._web = WebKit2.WebView()
            self._web.show()
            self.p_stack.add_named(self._web, "web")
        return self._web

    def _on_card_click(self, w, e, it, card):
        if e.button != 1:
            return False
        src = Gtk.get_event_widget(e)
        while src is not None and src is not w:
            if isinstance(src, Gtk.Button):   # the card's own buttons (PDF, star) handle themselves
                return False
            src = src.get_parent()
        if e.state & Gdk.ModifierType.CONTROL_MASK:
            open_uri(it["url"])
        else:
            if getattr(self, "_sel_card", None):
                self._sel_card.get_style_context().remove_class("selected")
            if self._is_showing(it, "abstract"):   # second click on the same paper closes the panel
                self._close_preview()
                return True
            card.get_style_context().add_class("selected")
            self._sel_card = card
            self._show_preview(it, "abstract")
        return True

    def _is_showing(self, it, mode):
        return (self.preview.get_visible() and self._p_item is not None and self._p_item["id"] == it["id"]
                and getattr(self, "_p_mode", None) == mode)

    def _select_card(self, widget, it, mode):
        if self._is_showing(it, mode):   # PDF button again: close
            self._close_preview()
            return
        card = widget
        while card and not card.get_style_context().has_class("card"):
            card = card.get_parent()
        if getattr(self, "_sel_card", None):
            self._sel_card.get_style_context().remove_class("selected")
        if card:
            card.get_style_context().add_class("selected")
            self._sel_card = card
        self._show_preview(it, mode)

    def _show_preview(self, it, mode="abstract"):
        """mode: abstract (title, authors, abstract, links), pdf (arXiv PDF), web (journal page)."""
        self._p_item, self._p_pdf, self._model = it, None, None
        self._p_token += 1
        tok = self._p_token
        self.p_title.set_text(tex2text(it["title"]))
        self.p_title.set_tooltip_text(tex2text(it["title"]))
        self.p_meta.set_text(f"{it.get('source', '')} · {it.get('date', '')} · " + ", ".join(it.get("authors", [])[:4]))
        self.preview.set_no_show_all(False)
        self.preview.show_all()
        if self.get_allocated_width() < 760:   # tiny tile: the preview takes the window, Esc goes back
            self.main_box.hide()
        if self.side_scroll.get_visible() and \
                self.paned.get_allocated_width() - self.main_box.get_preferred_width()[0] < 380:
            self.side_scroll.hide()   # make room for the panel; ☰ brings the list back
        if not getattr(self, "_paned_set", False):   # first preview: list 45%, PDF 55%
            minw = self.main_box.get_preferred_width()[0]
            self.paned.set_position(max(minw, int(self.paned.get_allocated_width() * 0.45)))
            self._paned_set = True
        if mode == "pdf" and it.get("oa") and not it.get("pdf") and EvinceView:
            pass   # open access: look up the publisher's PDF in the background (_fetch_pdf)
        elif mode == "pdf" and not (it.get("pdf") and EvinceView):
            mode = "web" if WebKit2 else "abstract"
        if mode == "web" and not WebKit2:
            mode = "abstract"
        is_pdf = mode == "pdf"
        self._p_mode = mode
        for b in (self.p_dl, self.p_ext, self.p_inv, self.p_zin, self.p_zout):
            b.set_visible(is_pdf)
        self.p_back.set_visible(mode != "abstract")
        self.p_title.set_visible(mode != "abstract")   # the abstract page shows the title itself
        self.p_meta.set_visible(mode != "abstract")
        if mode == "abstract":
            self.a_title.set_text(tex2text(it["title"]))
            au = it.get("authors", [])
            self.a_auth.set_text(", ".join(au) + (" et al." if len(au) >= 6 else ""))
            for c in self.a_chips.get_children():
                c.destroy()
            src = Gtk.Label(label=it.get("source", "") + (f" · {it['category']}" if it.get("category") else "")
                            + f" · {it.get('date', '')}")
            src.get_style_context().add_class("chip")
            src.get_style_context().add_class("src")
            src.set_halign(Gtk.Align.START)
            self.a_chips.add(src)
            for tp in it.get("topics", []):
                c = Gtk.Label(label=tp)
                c.get_style_context().add_class("chip")
                c.get_style_context().add_class("topic")
                c.set_halign(Gtk.Align.START)
                self.a_chips.add(c)
            self.a_text.set_text(tex2text(it.get("abstract", "")) or "No abstract in the feed — open the page.")
            self.a_pdf.set_visible(bool(it.get("pdf") or it.get("oa")) and EvinceView is not None)
            self.a_pdf.set_label("\U000f0226  Read PDF" if it.get("pdf") else "\U000f0fc6  Read here (open access)")
            self.a_web.set_visible(not it.get("pdf") and WebKit2 is not None)
            self.a_web.set_label("\U000f059f  Article page" if it.get("kind") == "news" else "\U000f059f  Journal page")
            self.a_cite.set_visible(it.get("kind") != "news")
            self._load_full_abstract(it, tok)
            self.p_stack.set_visible_child_name("abstract")
            self.p_stack.get_visible_child().get_vadjustment().set_value(0)
            self.a_chips.show_all()
        elif is_pdf:
            self.p_status.set_text("Loading PDF…")
            self.p_stack.set_visible_child_name("status")
            threading.Thread(target=self._fetch_pdf, args=(it, tok), daemon=True).start()
        else:
            self.web.load_uri(it["url"])
            self.p_stack.set_visible_child_name("web")

    def _fetch_pdf(self, it, tok):
        import re as _re
        os.makedirs(PDF_DIR, exist_ok=True)
        path = os.path.join(PDF_DIR, _re.sub(r"[^A-Za-z0-9.]+", "_", it["id"].replace("arxiv:", "")) + ".pdf")
        err = None
        if not os.path.exists(path):
            try:
                url = it.get("pdf") or RF.pdf_link(it)
                if not url:
                    raise ValueError("no PDF link registered")
                req = urllib.request.Request(url, headers={"User-Agent": RF.UA})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = r.read()
                if not data.startswith(b"%PDF"):
                    raise ValueError("the publisher sent a web page, not a PDF")
                with open(path + ".part", "wb") as fh:
                    fh.write(data)
                os.replace(path + ".part", path)
            except Exception as e:
                err = str(e)
        if err and not it.get("pdf") and WebKit2:   # open access without a PDF link: show the article page
            GLib.idle_add(lambda: tok == self._p_token and self._show_preview(it, "web") and False)
            return
        GLib.idle_add(self._pdf_ready, tok, path, err)

    def _load_full_abstract(self, it, tok):
        """Feeds often cut abstracts: fetch the whole one (arXiv by id, journals by DOI) and swap it in."""
        ab = it.get("abstract", "")
        cut = (not ab or ab.endswith(("…", "...")) or len(ab) in (1200, 1500)) and it.get("kind") != "news"
        if not cut or it.get("_full"):
            return

        def work():
            try:
                full = RF.full_abstract(it)
            except Exception:
                full = ""
            GLib.idle_add(done, full)

        def done(full):
            it["_full"] = True
            if full and len(full) > len(ab.rstrip("….")):
                it["abstract"] = full
                if tok == self._p_token and self._p_mode == "abstract":
                    self.a_text.set_text(tex2text(full))
            return False
        threading.Thread(target=work, daemon=True).start()

    def _pdf_ready(self, tok, path, err):
        if tok != self._p_token:
            return False
        if err:
            self.p_status.set_text("Couldn't download the PDF:\n" + err[:200])
            return False
        try:
            doc = EvinceDocument.Document.factory_get_document("file://" + path)
            m = EvinceView.DocumentModel.new_with_document(doc)
            m.set_sizing_mode(EvinceView.SizingMode.FIT_WIDTH)
            m.set_continuous(True)
            m.set_inverted_colors(self.p_inv.get_active())
            self.ev_view.set_model(m)
            self._model, self._p_pdf = m, path
            self.p_stack.set_visible_child_name("pdf")
        except Exception as e:
            self.p_status.set_text("Couldn't open the PDF:\n" + str(e)[:200])
        return False

    def _zoom(self, f):
        if self._model:
            self._model.set_sizing_mode(EvinceView.SizingMode.FREE)
            self._model.set_scale(self._model.get_scale() * f)

    def _save_pdf(self):
        if not self._p_pdf:
            return
        d = os.path.expanduser("~/Downloads/papers")
        os.makedirs(d, exist_ok=True)
        import re
        import shutil
        it = self._p_item
        name = re.sub(r"[^A-Za-z0-9]+", "_", (it.get("authors") or ["paper"])[0].split()[-1] + "_" +
                      tex2text(it["title"])[:60]).strip("_") + ".pdf"
        shutil.copy(self._p_pdf, os.path.join(d, name))
        self.p_dl.set_label("\U000f012c")
        GLib.timeout_add(2000, lambda: self.p_dl.set_label("\U000f01da") or False)

    def _close_preview(self):
        self.preview.hide()
        self.main_box.show()
        self._p_token += 1
        if getattr(self, "_sel_card", None):
            self._sel_card.get_style_context().remove_class("selected")

    # ---------- citations / Scholar
    # ---------- share
    def _share_button(self, label):
        b = Gtk.MenuButton(label=label)
        b.get_style_context().add_class("pill")
        b.set_tooltip_text("Share: copy the link, or send by email / WhatsApp / Telegram / X / Bluesky")
        menu = Gtk.Menu()
        for text, how in (("Copy link", "link"), ("Copy title + link", "text"), ("Email…", "mail"),
                          ("WhatsApp", "wa"), ("Telegram", "tg"), ("X (Twitter)", "x"), ("Bluesky", "bsky"),
                          ("LinkedIn", "li")):
            mi = Gtk.MenuItem(label=text)
            mi.connect("activate", lambda _m, h=how: self._share(h))
            menu.append(mi)
        menu.show_all()
        b.set_popup(menu)
        return b

    def _share(self, how):
        it = self._p_item
        if not it:
            return
        q = urllib.parse.quote
        title = tex2text(it["title"])
        au = it.get("authors", [])
        who = (au[0].split()[-1] + (" et al." if len(au) > 1 else "")) if au else ""
        url = it["url"]
        text = f"{title}" + (f" — {who}" if who else "") + f" ({it.get('source', '')})"
        if how in ("link", "text"):
            Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).set_text(url if how == "link" else f"{text}\n{url}", -1)
            self.vcount.set_text("copied to the clipboard")
            return
        links = {"mail": f"mailto:?subject={q(title)}&body={q(text + chr(10) + chr(10) + url)}",
                 "wa": f"https://wa.me/?text={q(text + ' ' + url)}",
                 "tg": f"https://t.me/share/url?url={q(url)}&text={q(text)}",
                 "x": f"https://twitter.com/intent/tweet?text={q(text)}&url={q(url)}",
                 "bsky": f"https://bsky.app/intent/compose?text={q(text + ' ' + url)}",
                 "li": f"https://www.linkedin.com/sharing/share-offsite/?url={q(url)}"}
        open_uri(links[how])

    # ---------- saved: folders and flairs
    def _fill_save_menu(self, btn):
        it = self._p_item
        if not it:
            return
        menu = Gtk.Menu()
        saved = self.state["saved"].get(it["id"])
        top = Gtk.MenuItem(label="\U000f04ce  Remove from saved" if saved else "\U000f04d2  Save")
        top.connect("activate", lambda *_: self._set_saved(it, not saved))
        menu.append(top)
        menu.append(Gtk.SeparatorMenuItem())
        hdr = Gtk.MenuItem(label="FOLDERS")
        hdr.set_sensitive(False)
        menu.append(hdr)
        for fo in self.state.setdefault("folders", []):
            ci = Gtk.CheckMenuItem(label=fo, active=bool(saved and fo in saved.get("folders", [])))
            ci.connect("toggled", lambda c, f=fo: self._in_folder(it, f, c.get_active()))
            menu.append(ci)
        nf = Gtk.MenuItem(label="＋ New folder…")
        nf.connect("activate", lambda *_: self._new_folder(it))
        menu.append(nf)
        menu.append(Gtk.SeparatorMenuItem())
        fl = Gtk.MenuItem(label="# My flair tags…" + (f"  ({', '.join(saved.get('flairs', []))})" if saved and saved.get("flairs") else ""))
        fl.connect("activate", lambda *_: self._edit_flairs(it))
        menu.append(fl)
        mg = Gtk.MenuItem(label="Manage folders…")
        mg.connect("activate", lambda *_: self._manage_folders())
        menu.append(mg)
        menu.show_all()
        btn.set_popup(menu)

    def _ensure_saved(self, it):
        if it["id"] not in self.state["saved"]:
            self.state["saved"][it["id"]] = dict(it, folders=[], flairs=[])
        return self.state["saved"][it["id"]]

    def _set_saved(self, it, on):
        if on:
            self._ensure_saved(it)
        else:
            self.state["saved"].pop(it["id"], None)
        self._after_saved_change()

    def _in_folder(self, it, folder, on):
        s = self._ensure_saved(it)
        fs = s.setdefault("folders", [])
        if on and folder not in fs:
            fs.append(folder)
        elif not on and folder in fs:
            fs.remove(folder)
        self._after_saved_change()

    def _ask(self, title, label, text=""):
        dlg = Gtk.Dialog(title=title, transient_for=self, modal=True)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_border_width(14)
        box.set_spacing(8)
        lab = Gtk.Label(label=label, xalign=0, wrap=True)
        lab.get_style_context().add_class("subtitle")
        box.pack_start(lab, False, False, 0)
        e = Gtk.Entry(text=text, activates_default=True, width_chars=40)
        box.pack_start(e, False, False, 0)
        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        ok = dlg.add_button("OK", Gtk.ResponseType.OK)
        ok.set_can_default(True)
        ok.grab_default()
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        out = e.get_text().strip() if dlg.run() == Gtk.ResponseType.OK else None
        dlg.destroy()
        return out

    def _new_folder(self, it=None):
        name = self._ask("New folder", "Folder name (e.g.  Thesis refs, To read, Group meeting)")
        if not name:
            return
        if name not in self.state.setdefault("folders", []):
            self.state["folders"].append(name)
        if it:
            self._in_folder(it, name, True)
        else:
            self._after_saved_change()

    def _edit_flairs(self, it):
        s = self._ensure_saved(it)
        known = sorted({f for i in self.state["saved"].values() for f in i.get("flairs", [])})
        got = self._ask("My flair tags", "Your own tags for this paper, comma separated"
                        + (f"\n(used so far: {', '.join(known)})" if known else "") + "\ne.g.  must read, method, my field",
                        ", ".join(s.get("flairs", [])))
        if got is None:
            return
        s["flairs"] = [x.strip() for x in got.split(",") if x.strip()]
        self._after_saved_change()

    def _manage_folders(self):
        dlg = Gtk.Dialog(title="Folders", transient_for=self, modal=True)
        dlg.set_default_size(420, 360)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_border_width(14)
        box.set_spacing(6)
        lab = Gtk.Label(label="Rename a folder by editing its name; the bin deletes it (the papers stay saved).",
                        xalign=0, wrap=True)
        lab.get_style_context().add_class("subtitle")
        box.pack_start(lab, False, False, 0)
        rows = []
        for fo in self.state.get("folders", []):
            r = Gtk.Box(spacing=6)
            e = Gtk.Entry(text=fo, hexpand=True)
            d = Gtk.ToggleButton(label="\U000f0a7a")
            d.set_tooltip_text("Delete this folder")
            d.get_style_context().add_class("iconbtn")
            r.pack_start(e, True, True, 0)
            r.pack_start(d, False, False, 0)
            box.pack_start(r, False, False, 0)
            rows.append((fo, e, d))
        add = self._pill("＋ New folder", lambda *_: (dlg.response(Gtk.ResponseType.APPLY)))
        box.pack_start(add, False, False, 0)
        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dlg.add_button("Save", Gtk.ResponseType.OK)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        resp = dlg.run()
        if resp == Gtk.ResponseType.OK:
            new = []
            for old, e, d in rows:
                name = e.get_text().strip()
                for it in self.state["saved"].values():
                    fs = it.get("folders", [])
                    if old in fs:
                        fs.remove(old)
                        if name and not d.get_active():
                            fs.append(name)
                if name and not d.get_active() and name not in new:
                    new.append(name)
            self.state["folders"] = new
            self._after_saved_change()
        dlg.destroy()
        if resp == Gtk.ResponseType.APPLY:
            self._new_folder()

    def _after_saved_change(self):
        self._save_state()
        self._build_side()
        self._render()

    # ---------- quick journal buttons (top bar)
    def _build_quick(self):
        for c in self.quick_box.get_children():
            c.destroy()
        conf = RF.load_conf() if os.path.exists(CONF) else {}
        want = conf.get("quick", ["arXiv", "PRL", "PRB", "PRX", "Rev. Mod. Phys."])
        have = set(["arXiv"] + list(conf.get("journals", {})))
        short = {"Rev. Mod. Phys.": "RMP"}
        for name in want:
            if name not in have:
                continue
            b = self._pill(short.get(name, name), lambda _b, n=name: self._goto(("source", n)))
            b.get_style_context().add_class("quick")
            b.set_tooltip_text(f"Show only {name}  (choose these buttons: \"quick\" in the config file)")
            self.quick_box.pack_start(b, False, False, 0)
        self.quick_box.show_all()

    def _goto(self, view):
        for r in self.side.get_children():
            if r.view == view:
                self.side.select_row(r)
                return
        self.view = view
        self._render()

    # ---------- first start
    def _welcome(self):
        dlg = Gtk.Dialog(title="Welcome to Physics Feed", transient_for=self, modal=True)
        dlg.set_default_size(620, 640)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_border_width(18)
        box.set_spacing(10)
        t = Gtk.Label(label="Welcome to Physics Feed", xalign=0)
        t.get_style_context().add_class("apptitle")
        box.pack_start(t, False, False, 0)
        lab = Gtk.Label(xalign=0, wrap=True, label=(
            "Pick the fields you work in. Physics Feed then follows the right arXiv categories and journals and "
            "sorts new papers into tags for you. You can change everything later with My topics and Journals & news."))
        lab.get_style_context().add_class("help-text")
        box.pack_start(lab, False, False, 0)
        sw = Gtk.ScrolledWindow(vexpand=True)
        v = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        sw.add(v)
        box.pack_start(sw, True, True, 0)
        checks = []
        for f, spec in FC.FIELDS.items():
            cb = Gtk.CheckButton()
            vv = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            vv.pack_start(Gtk.Label(label=f, xalign=0), False, False, 0)
            k = Gtk.Label(label=", ".join(spec["topics"]), xalign=0, ellipsize=Pango.EllipsizeMode.END)
            k.get_style_context().add_class("flair-kw")
            vv.pack_start(k, False, False, 0)
            cb.add(vv)
            cb.get_style_context().add_class("flair")
            v.pack_start(cb, False, False, 0)
            checks.append((cb, f))
        news = Gtk.CheckButton(label="Also follow physics news (APS Physics, Physics World, Quanta)", active=True)
        box.pack_start(news, False, False, 0)
        dlg.add_button("Start", Gtk.ResponseType.OK)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
            b.get_style_context().add_class("primary")
        dlg.show_all()
        dlg.run()
        chosen = [f for cb, f in checks if cb.get_active()] or ["Condensed matter theory"]
        dlg.destroy()
        conf = {"_help": RF.DEFAULT_CONF["_help"], "topics": {}, "arxiv_categories": [], "journals": {}, "news": {},
                "quick": ["arXiv", "PRL", "PRB", "PRX", "Rev. Mod. Phys."]}
        for f in chosen:
            spec = FC.FIELDS[f]
            conf["topics"][f] = spec["topics"]
            for c in spec["arxiv"]:
                if c not in conf["arxiv_categories"]:
                    conf["arxiv_categories"].append(c)
            for j in spec["journals"] + ["PRL", "PRB", "PRX", "Rev. Mod. Phys."]:
                if j in FC.JOURNALS:
                    conf["journals"].setdefault(j, FC.spec_of(j))
        if news.get_active():
            conf["news"] = dict(RF.DEFAULT_CONF["news"])
        os.makedirs(os.path.dirname(CONF), exist_ok=True)
        self._write_conf(conf)
        self._fill_topics()
        self._build_quick()
        self.sub.set_text("fetching your first papers… (about a minute)")
        self._refresh()

    def _cite_button(self, label):
        b = Gtk.MenuButton(label=label)
        b.get_style_context().add_class("pill")
        menu = Gtk.Menu()
        for text, fmt, how in (("Copy BibTeX", "bibtex", "copy"), ("Save BibTeX (.bib)…", "bibtex", "save"),
                               ("Add to a .bib file…", "bibtex", "append"), ("Copy RIS (Zotero, Mendeley, EndNote)", "ris", "copy"),
                               ("Save RIS (.ris)…", "ris", "save")):
            mi = Gtk.MenuItem(label=text)
            mi.connect("activate", lambda _m, f=fmt, h=how: self._cite(f, h))
            menu.append(mi)
        menu.show_all()
        b.set_popup(menu)
        return b

    def _cite(self, fmt, how):
        it = self._p_item
        if not it:
            return
        self.vcount.set_text("getting the citation…")

        def work():
            try:
                txt, err = RF.citation(it, fmt), None
            except Exception as e:
                txt, err = None, str(e)
            GLib.idle_add(done, txt, err)

        def done(txt, err):
            self._render_count()
            if err or not txt:
                self.vcount.set_text("couldn't get the citation: " + (err or "empty")[:80])
                return False
            if how == "copy":
                Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).set_text(txt, -1)
                self.vcount.set_text(("BibTeX" if fmt == "bibtex" else "RIS") + " copied to the clipboard")
                return False
            ext = ".bib" if fmt == "bibtex" else ".ris"
            action = Gtk.FileChooserAction.SAVE
            dlg = Gtk.FileChooserDialog(title="Add to which .bib file?" if how == "append" else "Save citation",
                                        transient_for=self, action=Gtk.FileChooserAction.OPEN if how == "append" else action)
            dlg.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Add" if how == "append" else "Save", Gtk.ResponseType.OK)
            dlg.set_current_folder(os.path.expanduser("~/Downloads"))
            if how != "append":
                import re
                first = (it.get("authors") or ["paper"])[0].split()[-1]
                dlg.set_current_name(re.sub(r"[^A-Za-z0-9]+", "_", f"{first}_{it.get('date', '')[:4]}") + ext)
                dlg.set_do_overwrite_confirmation(True)
            else:
                f = Gtk.FileFilter()
                f.set_name("BibTeX files")
                f.add_pattern("*.bib")
                dlg.add_filter(f)
            if dlg.run() == Gtk.ResponseType.OK:
                path = dlg.get_filename()
                with open(path, "a" if how == "append" else "w") as fh:
                    fh.write(("\n" if how == "append" else "") + txt + "\n")
                self.vcount.set_text(("added to " if how == "append" else "saved ") + os.path.basename(path))
            dlg.destroy()
            return False

        threading.Thread(target=work, daemon=True).start()

    def _scholar(self, it):
        open_uri("https://scholar.google.com/scholar?" + urllib.parse.urlencode({"q": '"' + tex2text(it["title"]) + '"'}))

    def _render_count(self):
        items = self._filtered()
        self.vcount.set_text(f"{len(items)} papers" if self.view != ("special", "search") else
                             (f"{len(items)} of {self.s_total:,} results" if self.s_args else ""))

    def _fill_topics(self):
        c = self.s_topic
        cur = c.get_active_id()
        c.remove_all()
        c.append("", "Any topic")
        for a in AREAS:
            c.append("a:" + a, "\U000f0768  " + a)
        conf = RF.load_conf()
        seen = set()
        for g, tps in conf["topics"].items():
            c.append("g:" + g, "\U000f04f9  " + g + " (my tags)")
            for t in tps:
                c.append("t:" + t, "      " + t)
                seen.add(t)
        for f, spec in FC.FIELDS.items():
            extra = [t for t in spec["topics"] if t not in seen]
            if extra:
                c.append("f:" + f, "\U000f0415  " + f)
                for t in extra:
                    c.append("x:" + f + "|" + t, "      " + t)
                    seen.add(t)
        c.set_active_id(cur or "")
        if c.get_active() < 0:
            c.set_active(0)

    def _topic_kws(self, tid):
        """(arXiv category or "", keywords) for a Topic filter id."""
        if not tid:
            return "", None
        kind, key = tid[0], tid[2:]
        conf = RF.load_conf()["topics"]
        if kind == "a":
            return AREAS[key]
        if kind == "g":
            return "", [k for kws in conf.get(key, {}).values() for k in kws]
        if kind == "t":
            for tps in conf.values():
                if key in tps:
                    return "", tps[key]
        if kind == "f":
            return "", [k for kws in FC.FIELDS[key]["topics"].values() for k in kws]
        if kind == "x":
            f, t = key.split("|", 1)
            return "", FC.FIELDS[f]["topics"][t]
        return "", None

    def _on_source(self):
        src = self.s_src.get_active_id()
        allowed = {"arxiv": ("all", "ti", "au", "abs", "id", "adv"), "journals": ("all", "au", "id"),
                   "scholar": ("all", "au")}[src]
        names = dict(SEARCH_FIELDS)
        names["id"] = "arXiv ID" if src == "arxiv" else "DOI"
        cur = self.s_field.get_active_id()
        self.s_field.remove_all()
        for k in allowed:
            self.s_field.append(k, names[k])
        self.s_field.set_active_id(cur if cur in allowed else "all")
        for w, show in ((self.s_cat, src == "arxiv"), (self.s_journal, src == "journals"), (self.s_sort, src != "scholar"),
                        (self.s_topic, src != "scholar")):
            w.get_parent().set_visible(show)   # FlowBoxChild
        if src == "journals":
            SEARCH_HINT["id"] = "a DOI, e.g.  10.1103/PhysRevB.98.075104"
        else:
            SEARCH_HINT["id"] = "e.g.  2511.01234   (several: comma separated)"
        self.s_entry.set_placeholder_text(SEARCH_HINT.get(self.s_field.get_active_id(), ""))

    # ---------- live arXiv search
    def _arxiv_search(self, more=False):
        if not more:
            text = self.s_entry.get_text().strip()
            tid = self.s_topic.get_active_id() or ""
            if not text and not tid:
                return
            src = self.s_src.get_active_id()
            field = self.s_field.get_active_id()
            if src == "scholar":   # Google Scholar has no open API: open the search in the browser
                q = f'author:"{text}"' if field == "au" else text
                open_uri("https://scholar.google.com/scholar?" + urllib.parse.urlencode({"q": q}))
                return
            extra = (self.s_cat.get_active_id() if src == "arxiv" else self.s_journal.get_active_id()) or ""
            cat_from_area, kws = self._topic_kws(tid)
            if src == "arxiv" and cat_from_area and not extra:
                extra, kws = cat_from_area, None   # a broad area on arXiv = its category
            elif src == "arxiv" and kws and not extra:
                # a tag without a category: stay inside your feed's categories (or the field's own)
                cats = FC.FIELDS[tid[2:].split("|")[0]]["arxiv"] if tid[0] in "fx" else RF.load_conf()["arxiv_categories"]
                extra = ",".join(cats)
            if src == "scholar":
                pass
            self.s_args = (src, text, field, extra, self.s_sort.get_active_id(), tuple(kws or ()))
            self.s_items, self.s_total, self.shown = [], 0, PAGE
        self.s_busy = True
        self._render()
        start = len(self.s_items)
        args = self.s_args

        def work():
            try:
                fn = RF.search_arxiv if args[0] == "arxiv" else RF.search_crossref
                items, total = fn(*args[1:5], start=start, n=50 if args[0] == "arxiv" else 40,
                                  topic_kws=list(args[5]) or None)
                err = None
            except Exception as e:
                items, total, err = [], 0, str(e)
            GLib.idle_add(done, items, total, err, args)

        def done(items, total, err, a):
            if a != self.s_args:
                return False
            compiled = RF.compile_topics(RF.load_conf()["topics"])
            for it in items:
                it["topics"] = RF.match(it["title"] + " " + it.get("abstract", ""), compiled)
            self.s_items += items
            self.s_total = total if not more or total else self.s_total
            self.s_busy = False
            self.shown = max(self.shown, len(self.s_items))
            v = self.scroll.get_vadjustment().get_value()
            self._render()
            if more:
                GLib.idle_add(lambda: self.scroll.get_vadjustment().set_value(v) or False)
            if err:
                self.vcount.set_text("arXiv didn't answer: " + err[:80])
            return False

        threading.Thread(target=work, daemon=True).start()

    # ---------- updates (from github.com/knight-dwell/physics-feed)
    def _auto_update_check(self):
        if not self.state.get("auto_update", True) or not updater.installed():
            return
        last = self.state.get("update_checked", "")
        if last and (dt.datetime.now() - dt.datetime.fromisoformat(last)).total_seconds() < 12 * 3600:
            return

        def work():
            try:
                newer, info = updater.update_available()
            except Exception:
                return
            self.state["update_checked"] = dt.datetime.now().isoformat(timespec="minutes")
            if not newer:
                return
            ok, log = updater.install(info["commit"])   # auto-update is on: install it now
            GLib.idle_add(self._show_update_bar,
                          f"\U000f06b0  Updated to the {info['date']} version — restart Physics Feed to use it" if ok else
                          "\U000f06b0  An update is available — click to install")
        threading.Thread(target=work, daemon=True).start()

    def _show_update_bar(self, text):
        self.update_bar.set_label(text)
        self.update_bar.set_no_show_all(False)
        self.update_bar.show_all()
        return False

    def _updates_dialog(self):
        dlg = Gtk.Dialog(title="Updates", transient_for=self, modal=True)
        dlg.set_default_size(480, 300)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_border_width(16)
        box.set_spacing(10)
        inst = updater.installed()
        lab = Gtk.Label(xalign=0, wrap=True, selectable=True)
        lab.get_style_context().add_class("help-text")
        box.pack_start(lab, False, False, 0)
        row = Gtk.Box(spacing=10)
        sw = Gtk.Switch(active=self.state.get("auto_update", True), valign=Gtk.Align.CENTER)
        row.pack_start(sw, False, False, 0)
        row.pack_start(Gtk.Label(label="Update automatically (checks twice a day, installs new versions)", xalign=0,
                                 wrap=True), True, True, 0)
        box.pack_start(row, False, False, 0)
        status = Gtk.Label(xalign=0, wrap=True)
        status.get_style_context().add_class("subtitle")
        box.pack_start(status, False, False, 0)
        btns = Gtk.Box(spacing=8)
        check = self._pill("Check now", None)
        go = self._pill("Update now", None, primary=True)
        go.set_sensitive(False)
        btns.pack_start(check, False, False, 0)
        btns.pack_start(go, False, False, 0)
        box.pack_start(btns, False, False, 0)
        found = {}

        def describe():
            if not inst:
                lab.set_text(f"Physics Feed {__version__}, running from a source folder.\n"
                             "Update it there with:  git pull && ./install.sh")
            else:
                lab.set_text(f"Physics Feed {__version__} · installed {inst.get('date', '?')} "
                             f"(version {inst.get('commit', '')[:7] or 'unknown'})\nUpdates come from github.com/{updater.REPO}")
        describe()

        def on_switch(w, _p):
            self.state["auto_update"] = w.get_active()
            self._save_state()
        sw.connect("notify::active", on_switch)

        def do_check(*_):
            status.set_text("checking…")
            check.set_sensitive(False)

            def work():
                try:
                    newer, info = updater.update_available()
                    msg = (f"New version from {info['date']}: {info['message']}" if newer else "You have the newest version.")
                except Exception as e:
                    newer, info, msg = False, {}, f"couldn't reach GitHub: {e}"
                GLib.idle_add(done, newer, info, msg)

            def done(newer, info, msg):
                status.set_text(msg)
                check.set_sensitive(True)
                go.set_sensitive(newer and bool(inst))
                found.update(info)
                return False
            threading.Thread(target=work, daemon=True).start()
        check.connect("clicked", do_check)

        def do_update(*_):
            status.set_text("downloading and installing…")
            go.set_sensitive(False)

            def work():
                try:
                    ok, log = updater.install(found["commit"])
                except Exception as e:
                    ok, log = False, str(e)
                GLib.idle_add(done, ok, log)

            def done(ok, log):
                status.set_text("Updated. Close and reopen Physics Feed to use the new version." if ok
                                else "The update failed:\n" + log[-400:])
                if ok:
                    self._show_update_bar("\U000f06b0  Updated — restart Physics Feed to use the new version")
                return False
            threading.Thread(target=work, daemon=True).start()
        go.connect("clicked", do_update)
        dlg.add_button("Close", Gtk.ResponseType.CLOSE)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        do_check()
        dlg.run()
        dlg.destroy()

    # ---------- dark / light
    def _apply_theme(self):
        mode = self.state.get("theme", "dark")
        screen = Gdk.Screen.get_default()
        if getattr(self, "_css", None):   # a fresh provider each time: GTK repaints reliably
            Gtk.StyleContext.remove_provider_for_screen(screen, self._css)
        self._css = Gtk.CssProvider()
        pal = palette()
        if mode == "light":
            pal.update(LIGHT)
        pal["radius_sm"] = max(int(pal.get("radius", 12)) - 4, 2)
        from string import Template
        css = Template(CSS).safe_substitute(pal) + (LIGHT_EXTRA if mode == "light" else "")
        self._css.load_from_data(css.encode())
        Gtk.StyleContext.add_provider_for_screen(screen, self._css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        Gtk.StyleContext.reset_widgets(screen)
        self.queue_draw()
        Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", mode == "dark")
        self.theme_btn.set_label("\U000f05a8" if mode == "dark" else "\U000f0594")
        self.theme_btn.set_tooltip_text("Light mode" if mode == "dark" else "Dark mode")

    def _toggle_theme(self):
        self.state["theme"] = "light" if self.state.get("theme", "dark") == "dark" else "dark"
        self._save_state()
        self._apply_theme()

    # ---------- field picker ("+ Add a field…")
    def _pick_field(self, parent):
        dlg = Gtk.Dialog(title="Add a research field", transient_for=parent, modal=True)
        dlg.set_default_size(620, 600)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_spacing(10)
        box.set_border_width(16)
        hint = Gtk.Label(xalign=0, wrap=True, label=(
            "1. Pick your field.   2. Tick the tags (topics) you want; each comes with sensible keywords "
            "you can edit afterwards.   3. Tick journals to follow."))
        hint.get_style_context().add_class("subtitle")
        box.pack_start(hint, False, False, 0)
        combo = Gtk.ComboBoxText()
        combo.get_style_context().add_class("pillcombo")
        for f in FC.FIELDS:
            combo.append(f, f)
        box.pack_start(combo, False, False, 0)
        sw = Gtk.ScrolledWindow(vexpand=True)
        inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        sw.add(inner)
        box.pack_start(sw, True, True, 0)
        checks = {"topics": [], "journals": []}

        def fill(*_):
            for c in inner.get_children():
                c.destroy()
            checks["topics"].clear()
            checks["journals"].clear()
            f = FC.FIELDS.get(combo.get_active_id())
            if not f:
                return
            h = Gtk.Label(label="TAGS", xalign=0)
            h.get_style_context().add_class("side-head")
            inner.pack_start(h, False, False, 0)
            for tname, kws in f["topics"].items():
                cb = Gtk.CheckButton(active=True)
                v = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
                v.pack_start(Gtk.Label(label=tname, xalign=0), False, False, 0)
                k = Gtk.Label(label=", ".join(kws), xalign=0, ellipsize=Pango.EllipsizeMode.END)
                k.get_style_context().add_class("flair-kw")
                v.pack_start(k, False, False, 0)
                cb.add(v)
                cb.get_style_context().add_class("flair")
                inner.pack_start(cb, False, False, 0)
                checks["topics"].append((cb, tname, kws))
            h = Gtk.Label(label="JOURNALS FOR THIS FIELD", xalign=0)
            h.get_style_context().add_class("side-head")
            inner.pack_start(h, False, False, 0)
            have = set(RF.load_conf()["journals"])
            for j in f["journals"]:
                if j not in FC.JOURNALS:
                    continue
                oa = "   \U000f0fc6 open access" if FC.JOURNALS[j][2] else ""
                cb = Gtk.CheckButton(label=j + ("   (already following)" if j in have else "") + oa, active=j not in have)
                cb.set_sensitive(j not in have)
                inner.pack_start(cb, False, False, 0)
                checks["journals"].append((cb, j))
            inner.show_all()
        combo.connect("changed", fill)
        combo.set_active(0)
        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dlg.add_button("Add", Gtk.ResponseType.OK)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        out = None
        if dlg.run() == Gtk.ResponseType.OK:
            out = (combo.get_active_id(),
                   {t: k for cb, t, k in checks["topics"] if cb.get_active()},
                   [j for cb, j in checks["journals"] if cb.get_active() and cb.get_sensitive()])
        dlg.destroy()
        return out

    def _follow_journals(self, names):
        full = load_json(CONF, {}) or dict(RF.DEFAULT_CONF)
        full.setdefault("journals", {})
        for n in names:
            if n in FC.JOURNALS:
                full["journals"].setdefault(n, FC.spec_of(n))
        self._write_conf(full)

    def _write_conf(self, full):
        with open(CONF + ".tmp", "w") as fh:
            json.dump(full, fh, indent=2, ensure_ascii=False)
        os.replace(CONF + ".tmp", CONF)

    # ---------- journals & news chooser
    def _edit_journals(self):
        full = load_json(CONF, {}) or dict(RF.DEFAULT_CONF)
        full.setdefault("journals", {})
        full.setdefault("news", {})
        dlg = Gtk.Dialog(title="Journals & news", transient_for=self, modal=True)
        dlg.set_default_size(760, 680)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_border_width(12)
        nb = Gtk.Notebook()
        box.pack_start(nb, True, True, 0)
        jchecks, nchecks = {}, {}

        def page(title, tip):
            sw = Gtk.ScrolledWindow(vexpand=True)
            v = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3, margin=10)
            sw.add(v)
            lab = Gtk.Label(label=title)
            lab.set_tooltip_text(tip)
            nb.append_page(sw, lab)
            return v

        # journals page
        v = page("Journals", "Tick the journals to follow")
        h = Gtk.Label(xalign=0, wrap=True, label="Tick to follow. \U000f0fc6 = open access (free to read, opens in the "
                      "app). \"Only my topics\" keeps just the papers that match your tags (good for big journals).")
        h.get_style_context().add_class("subtitle")
        v.pack_start(h, False, False, 0)
        groups = {}
        for name, (g, src, oa) in FC.JOURNALS.items():
            groups.setdefault(g, []).append(name)
        custom = [n for n in full["journals"] if n not in FC.JOURNALS]
        if custom:
            groups["Added by you"] = custom
        for g, names in groups.items():
            hd = Gtk.Label(label=g.upper(), xalign=0)
            hd.get_style_context().add_class("side-head")
            v.pack_start(hd, False, False, 0)
            for n in names:
                row = Gtk.Box(spacing=8)
                oa = FC.JOURNALS[n][2] if n in FC.JOURNALS else (len(full["journals"].get(n, [])) > 3 and full["journals"][n][3])
                cb = Gtk.CheckButton(label=n + ("   \U000f0fc6" if oa else ""), active=n in full["journals"])
                only = Gtk.CheckButton(label="only my topics",
                                       active=bool(full["journals"].get(n, [None, False])[1]))
                only.set_tooltip_text("Keep only papers matching your tags (for broad journals like Nature, Sci. Rep.)")
                row.pack_start(cb, True, True, 0)
                row.pack_end(only, False, False, 0)
                v.pack_start(row, False, False, 0)
                jchecks[n] = (cb, only)

        # news page
        v = page("News", "News feeds: shown in the News section, not mixed with papers")
        h = Gtk.Label(xalign=0, wrap=True, label="News appears in its own \"News\" section of the sidebar.")
        h.get_style_context().add_class("subtitle")
        v.pack_start(h, False, False, 0)
        ngroups = {}
        for name, (g, url) in FC.NEWS.items():
            ngroups.setdefault(g, []).append(name)
        for n, (url, g) in full["news"].items():
            if n not in FC.NEWS:
                ngroups.setdefault(g, []).append(n)
        for g, names in ngroups.items():
            hd = Gtk.Label(label=g.upper(), xalign=0)
            hd.get_style_context().add_class("side-head")
            v.pack_start(hd, False, False, 0)
            for n in names:
                cb = Gtk.CheckButton(label=n, active=n in full["news"])
                v.pack_start(cb, False, False, 0)
                nchecks[n] = cb

        # add your own
        v = page("＋ Add your own", "Add a journal or news site by its feed link or ISSN")
        guide = Gtk.Label(xalign=0, wrap=True, selectable=True, label=(
            "Paste one of these:\n"
            "• an RSS / Atom feed link — on the journal or news site look for the orange RSS icon, \"Subscribe\", "
            "\"Feeds\" or \"Latest articles RSS\" and copy that link.\n"
            "    APS:  http://feeds.aps.org/rss/recent/prl.xml   ·   Wiley: onlinelibrary.wiley.com/feed/<id>/most-recent\n"
            "• or the journal's ISSN (8 characters like 1361-648X) — shown on the journal's \"About\" page or on its "
            "Wikipedia page. This works for every publisher (IOP, Springer, Elsevier, Nature, ACS…), "
            "also those that block feed readers.\n"
            "Then give it a name, say whether it's a journal or news, and whether it's open access."))
        guide.get_style_context().add_class("help-text")
        v.pack_start(guide, False, False, 6)
        grid = Gtk.Grid(column_spacing=8, row_spacing=8)
        e_name = Gtk.Entry(placeholder_text="e.g.  Physical Review E", hexpand=True)
        e_src = Gtk.Entry(placeholder_text="feed link  or  ISSN (1234-567X)", hexpand=True)
        e_kind = Gtk.ComboBoxText()
        for k, t in (("journal", "Journal"), ("news", "News")):
            e_kind.append(k, t)
        e_kind.set_active(0)
        e_group = Gtk.Entry(placeholder_text="news group, e.g.  Physics news", hexpand=True)
        e_oa = Gtk.CheckButton(label="Open access (free to read)")
        e_only = Gtk.CheckButton(label="Only papers matching my topics")
        for r, (lab, w) in enumerate((("Name", e_name), ("Link / ISSN", e_src), ("Type", e_kind),
                                      ("News group", e_group), ("", e_oa), ("", e_only))):
            grid.attach(Gtk.Label(label=lab, xalign=0), 0, r, 1, 1)
            grid.attach(w, 1, r, 1, 1)
        v.pack_start(grid, False, False, 0)
        msg = Gtk.Label(xalign=0)
        msg.get_style_context().add_class("subtitle")
        added = []

        def add_own(*_):
            import re
            name, src = e_name.get_text().strip(), e_src.get_text().strip()
            if not name or not src:
                msg.set_text("Give a name and a link or ISSN.")
                return
            if re.fullmatch(r"\d{4}-?\d{3}[\dXx]", src):
                issn = src if "-" in src else src[:4] + "-" + src[4:]
                spec = ["crossref", e_only.get_active(), issn.upper(), e_oa.get_active()]
            elif src.startswith("http"):
                spec = [src, e_only.get_active(), "", e_oa.get_active()]
            else:
                msg.set_text("That's neither a link (http…) nor an ISSN (1234-567X).")
                return
            if e_kind.get_active_id() == "news":
                if spec[0] == "crossref":
                    msg.set_text("News needs a feed link, not an ISSN.")
                    return
                added.append(("news", name, [src, e_group.get_text().strip() or "My news"]))
            else:
                added.append(("journal", name, spec))
            msg.set_text(f"Added \"{name}\" — press Save; it shows up after the next refresh.")
            e_name.set_text("")
            e_src.set_text("")
        addb = self._pill("\U000f0415  Add", add_own, primary=True)
        v.pack_start(addb, False, False, 0)
        v.pack_start(msg, False, False, 0)

        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dlg.add_button("Save", Gtk.ResponseType.OK)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        if dlg.run() == Gtk.ResponseType.OK:
            js = {}
            for n, (cb, only) in jchecks.items():
                if cb.get_active():
                    spec = list(full["journals"].get(n) or FC.spec_of(n))
                    while len(spec) < 4:
                        spec.append("" if len(spec) == 2 else False)
                    spec[1] = only.get_active()
                    js[n] = spec
            ns = {}
            for n, cb in nchecks.items():
                if cb.get_active():
                    ns[n] = full["news"].get(n) or [FC.NEWS[n][1], FC.NEWS[n][0]]
            for kind, n, spec in added:
                (ns if kind == "news" else js)[n] = spec
            full["journals"], full["news"] = js, ns
            self._write_conf(full)
            try:
                RF.retag(CACHE)
            except (OSError, ValueError):
                pass
            self._load()
            self._refresh()   # fetch the newly ticked ones now
        dlg.destroy()

    # ---------- help
    def _help(self):
        dlg = Gtk.Dialog(title="How to use Physics Feed", transient_for=self, modal=True)
        dlg.set_default_size(640, 620)
        dlg.get_style_context().add_class("topics-dialog")
        sw = Gtk.ScrolledWindow(vexpand=True)
        lab = Gtk.Label(xalign=0, wrap=True, selectable=True, margin=16, use_markup=True, label=HELP)
        lab.get_style_context().add_class("help-text")
        sw.add(lab)
        dlg.get_content_area().pack_start(sw, True, True, 0)
        dlg.add_button("Got it", Gtk.ResponseType.OK)
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        dlg.run()
        dlg.destroy()

    # ---------- topics editor
    def _edit_topics(self):
        conf = RF.load_conf()
        dlg = Gtk.Dialog(title="My topics", transient_for=self, modal=True)
        dlg.set_default_size(820, 620)
        dlg.get_style_context().add_class("topics-dialog")
        box = dlg.get_content_area()
        box.set_spacing(10)
        box.set_border_width(16)
        head = Gtk.Label(xalign=0, wrap=True, label=(
            "Papers are sorted into these topics when a keyword appears in the title or abstract.\n"
            "Keywords: comma separated · case doesn't matter · short or ALL-CAPS words (DMFT, SSH) match whole words · "
            "a&b means both a and b must appear."))
        head.get_style_context().add_class("subtitle")
        box.pack_start(head, False, False, 0)
        sw = Gtk.ScrolledWindow(vexpand=True)
        grid = Gtk.Grid(column_spacing=8, row_spacing=6)
        sw.add(grid)
        box.pack_start(sw, True, True, 0)
        for col, t in enumerate(("Group", "Topic", "Keywords", "")):
            lab = Gtk.Label(label=t, xalign=0)
            lab.get_style_context().add_class("side-head")
            grid.attach(lab, col, 0, 1, 1)
        rows = []

        def add_row(group="", name="", kws=()):
            add_row.n = getattr(add_row, "n", 0) + 1
            r = add_row.n
            g = Gtk.ComboBoxText.new_with_entry()
            for gn in conf["topics"]:
                g.append_text(gn)
            g.get_child().set_text(group)
            g.get_child().set_width_chars(14)
            n = Gtk.Entry(text=name, width_chars=20)
            k = Gtk.Entry(text=", ".join(kws), hexpand=True)
            d = Gtk.Button(label="\U000f0a7a")
            d.get_style_context().add_class("iconbtn")
            d.set_tooltip_text("Remove this topic")
            row = [g, n, k, d]
            d.connect("clicked", lambda *_: ([w.destroy() for w in row], rows.remove(row)))
            for c, w in enumerate(row):
                grid.attach(w, c, r, 1, 1)
            rows.append(row)
            grid.show_all()
            return n

        for gname, tps in conf["topics"].items():
            for tname, kws in tps.items():
                add_row(gname, tname, kws)
        bar = Gtk.Box(spacing=8)
        add = self._pill("\U000f0415  Add topic", lambda *_: add_row("My research", "", ()).grab_focus())
        add.set_tooltip_text("An empty row: type a group, a topic name and its keywords")

        def add_field(*_):
            got = self._pick_field(dlg)
            if got:
                field, topics, journals = got
                for tname, kws in topics.items():
                    add_row(field, tname, kws)
                if journals:
                    self._follow_journals(journals)
        fieldb = self._pill("\U000f0415  Add a field…", add_field, primary=True)
        fieldb.set_tooltip_text("Pick a research field (theory, experiment, soft matter, stat mech, …) and tick its tags")
        bar.pack_start(fieldb, False, False, 0)
        clear = self._pill("Start from empty", lambda *_: [[w.destroy() for w in r] for r in rows] and rows.clear())
        clear.set_tooltip_text("Remove every topic (e.g. to set it up for someone else)")
        raw = self._pill("Edit file", lambda *_: open_uri("file://" + CONF))
        for w in (add, clear, raw):
            bar.pack_start(w, False, False, 0)
        box.pack_start(bar, False, False, 0)
        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        okb = dlg.add_button("Save", Gtk.ResponseType.OK)
        okb.get_style_context().add_class("primary")
        for b in dlg.get_action_area().get_children():
            b.get_style_context().add_class("pill")
        dlg.show_all()
        resp = dlg.run()
        if resp == Gtk.ResponseType.OK:
            topics = {}
            for g, n, k, _ in rows:
                gname = g.get_child().get_text().strip() or "My research"
                tname = n.get_text().strip()
                kws = [x.strip() for x in k.get_text().split(",") if x.strip()]
                if tname and kws:
                    topics.setdefault(gname, {})[tname] = kws
            full = load_json(CONF, {}) or dict(RF.DEFAULT_CONF)
            full["topics"] = topics
            with open(CONF + ".tmp", "w") as fh:
                json.dump(full, fh, indent=2, ensure_ascii=False)
            os.replace(CONF + ".tmp", CONF)
            try:
                RF.retag(CACHE)
            except (OSError, ValueError):
                pass
            self._load()
            self._fill_topics()
        dlg.destroy()

    # ---------- events
    def _on_search(self, entry):
        self.query = entry.get_text()
        self.shown = PAGE
        self._render()

    def _on_edge(self, sw, pos):
        if (pos == Gtk.PositionType.BOTTOM and self.view == ("special", "search") and not self.s_busy
                and self.s_args and len(self.s_items) < self.s_total):
            self._arxiv_search(more=True)
            return
        if pos == Gtk.PositionType.BOTTOM and self.shown < len(self._filtered()):
            self.shown += PAGE
            v = self.scroll.get_vadjustment().get_value()
            self._render()
            GLib.idle_add(lambda: self.scroll.get_vadjustment().set_value(v) or False)

    def _on_size(self, w, alloc):
        narrow = alloc.width < 900
        if narrow != self._narrow:   # small tile: hide the topic list (the ☰ button brings it back)
            self._narrow = narrow
            GLib.idle_add(lambda: self.side_scroll.set_visible(not narrow) or False)

    def _on_key(self, w, ev):
        if ev.state & Gdk.ModifierType.CONTROL_MASK and Gdk.keyval_name(ev.keyval) == "f":
            self.search.grab_focus()
            return True
        if Gdk.keyval_name(ev.keyval) == "Escape" and self.search.get_text():
            self.search.set_text("")
            return True
        if Gdk.keyval_name(ev.keyval) == "Escape" and self.preview.get_visible():
            self._close_preview()
            return True
        return False

    def _refresh(self, *a):
        self.refresh_btn.set_sensitive(False)
        self.refresh_btn.set_label("\U000f0450 …")

        def work():
            subprocess.run([sys.executable, "-m", "physics_feed.feed", "--quiet"], capture_output=True,
                           cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            GLib.idle_add(done)

        def done():
            self.refresh_btn.set_sensitive(True)
            self.refresh_btn.set_label("\U000f0450")
            self._load()
            return False

        threading.Thread(target=work, daemon=True).start()

    def _on_close(self, *a):
        self.state["last_open"] = dt.datetime.now().isoformat(timespec="minutes")
        self._save_state()
        Gtk.main_quit()


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(prog="physics-feed", description="Physics Feed: new papers sorted into your topics")
    ap.add_argument("--refresh", action="store_true", help="fetch new papers now (no window)")
    ap.add_argument("--quiet", action="store_true", help="with --refresh: no desktop notification")
    ap.add_argument("--version", action="version", version=f"Physics Feed {__version__}")
    args = ap.parse_args(argv)
    if args.refresh:
        sys.argv = ["physics-feed"] + (["--quiet"] if args.quiet else [])
        return RF.main()
    GLib.set_prgname("physics-feed")
    GLib.set_application_name("Physics Feed")
    first_run = not os.path.exists(CONF)
    w = Feed()
    w.show_all()
    if first_run:
        GLib.idle_add(lambda: w._welcome() or False)
    Gtk.main()
    return 0


if __name__ == "__main__":
    sys.exit(main())
