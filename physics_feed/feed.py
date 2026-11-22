#!/usr/bin/env python3
"""Physics Feed fetcher: new papers from arXiv and journals (+ news), sorted into your topics.

  physics-feed --refresh          fetch everything now (the app and a timer call this)
  python3 -m physics_feed.feed    same

Settings: ~/.config/physics-feed/config.json (made by the app's welcome screen).
A keyword hits when it appears in the title or abstract (case-insensitive; short or
ALL-CAPS keywords like DMFT, DFT, SSH must be whole words; "a&b" needs both a and b).
Journals: an RSS url, or "crossref" + ISSN for publishers without an open feed (IOP...).
Papers are kept for KEEP_DAYS days. The Physics Feed window (rice-feed-app) and the
top-bar Research menu read the cache; the app also uses search_arxiv / search_crossref /
citation from here.
"""
import datetime as dt
import email.utils
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

try:
    from .catalog import FIELDS
except ImportError:   # run as a plain script
    from catalog import FIELDS

CONF_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "physics-feed")
CONF = os.path.join(CONF_DIR, "config.json")
CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "physics-feed")
CACHE = os.path.join(CACHE_DIR, "feed.json")
MENU_CACHE = os.path.join(CACHE_DIR, "menu.json")   # short "papers for you" list (desktop widgets can read it)
KEEP_DAYS = 6
OTHER_DAYS = 6
UA = "Mozilla/5.0 (X11; Linux x86_64) physics-feed/1.0"

DEFAULT_CONF = {
    "_help": "topics: group -> topic -> keywords. journals: name -> [RSS url, filter] or [\"crossref\", filter, ISSN]. "
             "filter true = keep only papers that hit a topic (for broad journals). arxiv_categories: searched on arXiv.",
    "topics": {"Condensed matter theory": FIELDS["Condensed matter theory"]["topics"]},   # the welcome screen replaces this
    "arxiv_categories": ["cond-mat", "quant-ph", "hep-th", "physics.comp-ph", "physics.optics"],
    "news": {
        "Physics (APS)": ["https://feeds.aps.org/rss/recent/physics.xml", "Physics news"],
        "Physics World": ["https://physicsworld.com/feed/", "Physics news"],
        "Quanta Magazine": ["https://www.quantamagazine.org/feed/", "Physics news"],
    },
    "journals": {
        "PRL": ["https://feeds.aps.org/rss/recent/prl.xml", False],
        "PRB": ["https://feeds.aps.org/rss/recent/prb.xml", False],
        "PRX": ["https://feeds.aps.org/rss/recent/prx.xml", False],
        "PR Research": ["https://feeds.aps.org/rss/recent/prresearch.xml", True],
        "PRA": ["https://feeds.aps.org/rss/recent/pra.xml", True],
        "PR Applied": ["https://feeds.aps.org/rss/recent/prapplied.xml", True],
        "Rev. Mod. Phys.": ["https://feeds.aps.org/rss/recent/rmp.xml", False],
        "Nature": ["crossref", True, "1476-4687"],
        "Nature Physics": ["crossref", False, "1745-2481"],
        "Nature Photonics": ["crossref", False, "1749-4893"],
        "Nature Materials": ["crossref", False, "1476-4660"],
        "Nature Communications": ["crossref", True, "2041-1723"],
        "npj Quantum Materials": ["crossref", False, "2397-4648"],
        "Scientific Reports": ["crossref", True, "2045-2322"],
        "Advanced Materials": ["https://onlinelibrary.wiley.com/feed/15214095/most-recent", True],
        "Adv. Theory & Simulations": ["https://onlinelibrary.wiley.com/feed/25130390/most-recent", False],
        "J. Phys.: Condens. Matter": ["crossref", False, "1361-648X"],
        "J. Phys. A: Math. Theor.": ["crossref", False, "1751-8121"],
        "New J. Phys.": ["crossref", False, "1367-2630"],
        "Rep. Prog. Phys.": ["crossref", False, "1361-6633"],
        "J. Phys.: Photonics": ["crossref", False, "2515-7647"],
        "J. Optics": ["crossref", False, "2040-8986"],
        "2D Materials": ["crossref", False, "2053-1583"],
        "EPL": ["crossref", True, "1286-4854"],
        "J. Math. Phys.": ["crossref", False, "1089-7658"],
        "Rev. Math. Phys.": ["crossref", False, "1793-6659"],
        "Commun. Math. Phys.": ["crossref", False, "1432-0916"],
        "Lett. Math. Phys.": ["crossref", False, "1573-0530"],
        "Ann. Henri Poincaré": ["crossref", False, "1424-0661"],
        "Quantum": ["https://quantum-journal.org/feed/", False],
        "Applied Physics Letters": ["https://pubs.aip.org/rss/site_1000017/1000011.xml", True],
    },
}

# ISSNs for "search one journal" (Crossref)
SEARCH_JOURNALS = {
    "PRL": "0031-9007", "PRB": "2469-9950", "PRX": "2160-3308", "PRA": "2469-9926", "PR Research": "2643-1564",
    "PR Applied": "2331-7019", "Rev. Mod. Phys.": "0034-6861", "Nature": "0028-0836", "Nature Physics": "1745-2473",
    "Nature Photonics": "1749-4885", "Nature Materials": "1476-1122", "Nature Communications": "2041-1723",
    "npj Quantum Materials": "2397-4648", "Scientific Reports": "2045-2322", "Science": "0036-8075",
    "Science Advances": "2375-2548", "Advanced Materials": "0935-9648", "Adv. Theory & Simulations": "2513-0390",
    "J. Phys.: Condens. Matter": "1361-648X", "J. Phys. A: Math. Theor.": "1751-8121", "New J. Phys.": "1367-2630",
    "Rep. Prog. Phys.": "1361-6633", "J. Phys.: Photonics": "2515-7647", "J. Optics": "2040-8986",
    "2D Materials": "2053-1583", "EPL": "1286-4854", "J. Math. Phys.": "1089-7658", "Rev. Math. Phys.": "1793-6659",
    "Commun. Math. Phys.": "1432-0916", "Lett. Math. Phys.": "1573-0530", "Ann. Henri Poincaré": "1424-0661",
    "Quantum": "2521-327X", "Applied Physics Letters": "1077-3118", "Optica": "2334-2536",
    "Light: Science & Applications": "2047-7538", "Phys. Rep.": "0370-1573", "Annals of Physics": "0003-4916",
    "SciPost Physics": "2542-4653",
}


def load_conf():
    os.makedirs(CONF_DIR, exist_ok=True)
    os.makedirs(CACHE_DIR, exist_ok=True)
    if not os.path.exists(CONF):
        with open(CONF, "w") as fh:
            json.dump(DEFAULT_CONF, fh, indent=2, ensure_ascii=False)
    with open(CONF) as fh:
        c = json.load(fh)
    return {**DEFAULT_CONF, **c}


# ---------- keyword matching ----------
def compile_topics(topics):
    out = []
    for group, tps in topics.items():
        for name, kws in tps.items():
            pats = []
            for kw in kws:
                parts = []
                for p in kw.split("&"):
                    p = p.strip()
                    whole = len(p) <= 5 or (p.isupper() and len(p) <= 8)
                    flags = 0 if p.isupper() else re.I
                    rx = (r"(?<![A-Za-z0-9])" + re.escape(p) + r"(?![A-Za-z0-9])") if whole else re.escape(p)
                    parts.append(re.compile(rx, flags))
                pats.append(parts)
            out.append((group, name, pats))
    return out


def match(text, compiled):
    hits = []
    for group, name, pats in compiled:
        if any(all(rx.search(text) for rx in parts) for parts in pats):
            hits.append(name)
    return hits


# ---------- fetching ----------
_CROSSREF = __import__("threading").Semaphore(2)   # Crossref allows only a couple of parallel requests


def get(url, timeout=40, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    gate = _CROSSREF if "api.crossref.org" in url else None
    for attempt in range(4):
        try:
            if gate:
                with gate:
                    with urllib.request.urlopen(req, timeout=timeout) as r:
                        return r.read()
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 3:   # 429 = too many requests: wait and try again
                raise
            time.sleep(float(e.headers.get("Retry-After") or 2 * (attempt + 1)))


def clean(s):
    s = html.unescape(re.sub(r"<[^>]+>", " ", s or ""))
    return " ".join(s.split())


def local(tag):
    return tag.rsplit("}", 1)[-1]


def child_text(el, *names):
    for c in el:
        if local(c.tag) in names and (c.text or "").strip():
            return c.text.strip()
    for c in el:   # atom <link href>
        if local(c.tag) in names and c.get("href"):
            return c.get("href")
    return ""


def parse_date(s):
    if not s:
        return ""
    s = s.strip()
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")[:25]).date().isoformat()
    except ValueError:
        pass
    try:
        return email.utils.parsedate_to_datetime(s).date().isoformat()
    except (TypeError, ValueError):
        m = re.search(r"\d{4}-\d{2}-\d{2}", s)
        return m.group(0) if m else ""


IMG = re.compile(r"""<img[^>]+src=["']([^"']+)["']""", re.I)
OG = re.compile(r"""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*content=["']([^"']+)["']""", re.I)
OG2 = re.compile(r"""<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["'](?:og:image|twitter:image)["']""", re.I)


def og_image(url):
    """The article page's preview picture (og:image), reading only the start of the page."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as r:
        head = r.read(96 * 1024).decode("utf-8", "replace")
    m = OG.search(head) or OG2.search(head)
    return html.unescape(m.group(1)) if m else ""


def find_doi(*texts):
    for t in texts:
        m = re.search(r"10\.\d{4,9}/[^\s\"<>?#]+", t or "")
        if m:
            return m.group(0).rstrip(".,;)")
    return ""


def fetch_rss(name, url):
    for attempt in range(3):   # nature.com sometimes answers a burst of requests with a non-feed page
        try:
            root = ET.fromstring(get(url))
            break
        except ET.ParseError:
            if attempt == 2:
                raise
            time.sleep(4 * (attempt + 1))
    items = [e for e in root.iter() if local(e.tag) in ("item", "entry")]
    out = []
    for it in items:
        title = clean(child_text(it, "title"))
        link = child_text(it, "link", "id")
        if not title or not link:
            continue
        authors = [clean(c.text) for c in it if local(c.tag) in ("creator", "author") and (c.text or "").strip()]
        if len(authors) == 1 and ("," in authors[0] or " and " in authors[0]):
            authors = [a.strip() for a in re.split(r",| and ", authors[0]) if a.strip()]
        abstract = clean(child_text(it, "description", "summary", "encoded", "abstract"))
        # Nature puts "Nature Physics, Published online: ...; doi:..." in the description: drop that
        abstract = re.sub(r"^.*?doi:\S+\s*", "", abstract) if abstract.startswith(name) else abstract
        raw = child_text(it, "description")
        if raw.lstrip().startswith("Author(s):") and "<br" in raw:
            abstract = clean(raw.split("<br", 1)[1].split(">", 1)[-1])                                    # APS
        abstract = re.sub(r"\s*\[Phys\. Rev\..*$", "", abstract)                                            # APS tail
        abstract = re.sub(r"^[A-Z][\w .&]+, Volume \d+, Issue \d+[^.]*\.\s*", "", abstract)                 # Wiley head
        date = parse_date(child_text(it, "date", "pubDate", "published", "updated", "issued"))
        doi = find_doi(child_text(it, "doi", "identifier"), link)
        image = ""
        for c in it:   # media:thumbnail / media:content / <enclosure type="image/...">
            t = local(c.tag)
            if c.get("url") and (t == "thumbnail" or t == "content" or (t == "enclosure" and (c.get("type") or "").startswith("image"))):
                image = c.get("url")
                break
        if not image:
            m = IMG.search(raw + " " + child_text(it, "encoded"))
            image = m.group(1) if m else ""
        item = {"id": link, "source": name, "kind": "journal", "title": title,
                "authors": authors[:6], "abstract": abstract[:6000], "url": link, "doi": doi,
                "date": date or dt.date.today().isoformat()}
        if image:
            item["image"] = html.unescape(image)
        out.append(item)
    return out


# ---------- Crossref (journals without an open feed, and journal search) ----------
CR_FIELDS = "DOI,title,author,published,published-online,issued,abstract,container-title,type"


def crossref_item(w, source=None):
    """One Crossref work -> feed item."""
    doi = w.get("DOI", "")
    parts = (w.get("published") or w.get("published-online") or w.get("issued") or {}).get("date-parts", [[]])[0]
    parts = [p for p in parts if p]
    date = "-".join(f"{x:02d}" if i else str(x) for i, x in enumerate(parts)) if parts else ""
    if len(date) == 4:
        date += "-01-01"
    elif len(date) == 7:
        date += "-01"
    return {"id": "doi:" + doi.lower(), "source": source or (w.get("container-title") or ["Journal"])[0],
            "kind": "journal", "title": clean(" ".join(w.get("title") or [""])),
            "authors": [" ".join(x for x in (a.get("given"), a.get("family")) if x) or a.get("name", "")
                        for a in w.get("author", [])][:6],
            "abstract": re.sub(r"^Abstract\s*", "", clean(w.get("abstract", "")))[:6000],
            "url": "https://doi.org/" + doi, "doi": doi, "date": date or dt.date.today().isoformat()}


def fetch_crossref(name, issn, since):
    """Newest papers of one journal (by ISSN) from Crossref."""
    q = urllib.parse.urlencode({"filter": f"from-pub-date:{since},type:journal-article", "sort": "published",
                                "order": "desc", "rows": 100, "select": CR_FIELDS})
    data = json.loads(get(f"https://api.crossref.org/journals/{issn}/works?{q}"))
    return [crossref_item(w, name) for w in data["message"]["items"] if w.get("title")]


def search_crossref(text, field="all", issn="", sort="relevance", start=0, n=40, topic_kws=None):
    """Live search of (nearly) all journals via Crossref. field: all/ti/abs -> bibliographic, au -> author, id -> DOI."""
    if field == "id":
        doi = find_doi(text) or text.strip()
        w = json.loads(get("https://api.crossref.org/works/" + urllib.parse.quote(doi)))["message"]
        return [crossref_item(w)], 1
    params = {"rows": n, "offset": start, "select": CR_FIELDS,
              "filter": "type:journal-article" + (f",issn:{issn}" if issn else "")}
    if topic_kws:   # Crossref has no OR: steer the query with the topic words, then keep only real matches
        plain = [k.replace("&", " ") for k in topic_kws[:6]]
        if field == "au":
            params["query.bibliographic"] = " ".join(plain)
        else:
            text = (text + " " + " ".join(plain)).strip()
        params["rows"] = 100
    params["query.author" if field == "au" else "query.bibliographic"] = text
    if sort == "newest":
        params.update(sort="published", order="desc")
    if field == "au":
        params["rows"] = min(n * 3, 200)   # Crossref's author search is fuzzy: fetch more, keep real matches
    data = json.loads(get("https://api.crossref.org/works?" + urllib.parse.urlencode(params), timeout=60))["message"]
    items = [w for w in data["items"] if w.get("title")]
    if field == "au":
        wanted = []
        for name in re.split(r"[,;]| and ", text):
            words = [x.strip(".").lower() for x in name.split() if x.strip(".")]
            if words:
                wanted.append((words[-1], words[0][0] if len(words) > 1 else ""))

        def has(w, last, ini):
            return any((a.get("family") or "").lower() == last and
                       (not ini or (a.get("given") or " ").lower().startswith(ini)) for a in w.get("author", []))
        items = [w for w in items if all(has(w, last, ini) for last, ini in wanted)]
    if topic_kws:
        comp = compile_topics({"t": {"t": topic_kws}})
        items = [w for w in items if match(clean(" ".join(w.get("title") or [])) + " " + clean(w.get("abstract", "")), comp)]
        return [crossref_item(w) for w in items], len(items)
    return [crossref_item(w) for w in items], data.get("total-results", 0) if field != "au" else len(items)


def citation(item, fmt="bibtex"):
    """BibTeX or RIS for a paper: the publisher's record via its DOI, else arXiv's own BibTeX."""
    accept = {"bibtex": "application/x-bibtex", "ris": "application/x-research-info-systems"}[fmt]
    doi = item.get("doi")
    if doi:
        try:
            return get("https://doi.org/" + doi, timeout=30, headers={"Accept": accept}).decode("utf-8", "replace").strip()
        except Exception:   # fall back to Crossref's own converter
            return get(f"https://api.crossref.org/works/{urllib.parse.quote(doi)}/transform/{accept}",
                       timeout=30).decode("utf-8", "replace").strip()
    aid = item.get("id", "").replace("arxiv:", "")
    if fmt == "bibtex" and item.get("kind") == "arxiv":
        return get("https://arxiv.org/bibtex/" + aid).decode("utf-8", "replace").strip()
    lines = ["TY  - JOUR"] + [f"AU  - {a}" for a in item.get("authors", [])] + [
        f"TI  - {item.get('title', '')}", f"PY  - {item.get('date', '')[:4]}", f"DA  - {item.get('date', '')}",
        f"JO  - {item.get('source', '')}", f"UR  - {item.get('url', '')}", f"N2  - {item.get('abstract', '')}", "ER  - "]
    return "\n".join(lines)


# ---------- arXiv ----------
def arxiv_entries(root):
    ns = {"a": "http://www.w3.org/2005/Atom"}
    out = []
    for e in root.findall("a:entry", ns):
        title = " ".join(e.findtext("a:title", "", ns).split())
        if not title or title == "Error":
            continue
        pid = re.sub(r"v\d+$", "", e.findtext("a:id", "", ns).rsplit("/abs/", 1)[-1])
        cat = e.find("{http://arxiv.org/schemas/atom}primary_category")
        out.append({"id": "arxiv:" + pid, "source": "arXiv", "kind": "arxiv",
                    "category": cat.get("term") if cat is not None else "",
                    "title": title,
                    "authors": [a.findtext("a:name", "", ns) for a in e.findall("a:author", ns)][:6],
                    "abstract": " ".join(e.findtext("a:summary", "", ns).split()),
                    "url": "https://arxiv.org/abs/" + pid, "pdf": "https://arxiv.org/pdf/" + pid,
                    "doi": e.findtext("{http://arxiv.org/schemas/atom}doi", "") or "",
                    "date": e.findtext("a:published", "", ns)[:10]})
    return out


def fetch_arxiv(cats, since, page=1000, max_pages=20):
    """All papers in these categories submitted on/after `since` (YYYY-MM-DD), newest first,
    fetched page by page (arXiv asks for ~3 s between requests)."""
    q = " OR ".join(f"cat:{c}*" if "." not in c else f"cat:{c}" for c in dict.fromkeys(cats))
    q = f"({q}) AND submittedDate:[{since.replace('-', '')}0000 TO 209912312359]"
    ns = {"a": "http://www.w3.org/2005/Atom"}
    out = []
    for n in range(max_pages):
        url = "https://export.arxiv.org/api/query?" + urllib.parse.urlencode({
            "search_query": q, "sortBy": "submittedDate", "sortOrder": "descending",
            "start": n * page, "max_results": page})
        root, count = None, 0
        for attempt in range(3):   # the API sometimes returns an empty page; retry
            root = ET.fromstring(get(url, timeout=120))
            count = len(root.findall("a:entry", ns))
            if count:
                break
            time.sleep(5)
        out += arxiv_entries(root)
        if count < page:
            break
        time.sleep(3)
    return out


def topic_clause(kws, limit=18):
    """arXiv query part matching any of a topic's keywords in title or abstract ("a&b" = both)."""
    ors = []
    for kw in kws[:limit]:
        parts = [p.strip().replace('"', "") for p in kw.split("&") if p.strip()]
        if parts:
            ors.append("(" + " AND ".join(f'all:"{p}"' for p in parts) + ")" if len(parts) > 1 else f'all:"{parts[0]}"')
    return "(" + " OR ".join(ors) + ")" if ors else ""


def build_query(text, field="all", cat="", topic_kws=None):
    """arXiv API search_query from what the user typed. field: all, ti, au, abs, adv (raw query)."""
    text = text.strip()
    if field == "adv":
        q = text
    elif field == "au":
        parts = []
        for name in re.split(r"[,;]| and ", text):
            words = [w.strip(".") for w in name.split() if w.strip(".")]
            if not words:
                continue
            if len(words) == 1:
                parts.append(f"au:{words[0]}")
            elif len(words[0]) <= 2:   # "P. Coleman" -> au:Coleman_P (any P. Coleman)
                parts.append(f"au:{words[-1]}_{words[0][0]}")
            else:                      # "Piers Coleman" -> exactly that person
                parts.append('au:"' + " ".join(words) + '"')
        q = " AND ".join(parts)
    else:
        terms = re.findall(r'"[^"]+"|\S+', text)
        q = " AND ".join(f"{field}:{t}" for t in terms)
    if cat:   # one category, or several separated by commas (OR)
        cs = [x.strip() for x in cat.split(",") if x.strip()]
        c = " OR ".join(f"cat:{x}*" if "." not in x else f"cat:{x}" for x in cs)
        c = f"({c})" if len(cs) > 1 else c
        q = f"({q}) AND {c}" if q else c
    if topic_kws:
        t = topic_clause(topic_kws)
        q = f"({q}) AND {t}" if q else t
    return q


def search_arxiv(text, field="all", cat="", sort="relevance", start=0, n=50, topic_kws=None):
    """Live search of the whole arXiv. Returns (items, total). field "id": arXiv ids, comma separated."""
    params = {"start": start, "max_results": n}
    if field == "id":
        params["id_list"] = ",".join(re.sub(r"^(arxiv:|https?://arxiv.org/(abs|pdf)/)", "", x.strip(), flags=re.I)
                                     for x in text.split(",") if x.strip())
    else:
        params["search_query"] = build_query(text, field, cat, topic_kws)
        params["sortBy"] = "submittedDate" if sort == "newest" else "relevance"
        params["sortOrder"] = "descending"
    root = ET.fromstring(get("https://export.arxiv.org/api/query?" + urllib.parse.urlencode(params), timeout=60))
    total = int(root.findtext("{http://a9.com/-/spec/opensearch/1.1/}totalResults", "0") or 0)
    return arxiv_entries(root), total


def _safe(fn):
    try:
        return fn()
    except Exception:
        return ""


def news_groups(conf):
    out = {}
    for name, (url, group) in conf.get("news", {}).items():
        out.setdefault(group, []).append(name)
    return out


def full_abstract(item):
    """The complete abstract (feeds often cut it): arXiv by id, journals via Crossref by DOI."""
    if item.get("kind") == "arxiv":
        got, _ = search_arxiv(item["id"].replace("arxiv:", ""), "id")
        return got[0]["abstract"] if got else ""
    if item.get("doi"):
        w = json.loads(get("https://api.crossref.org/works/" + urllib.parse.quote(item["doi"]), timeout=30))["message"]
        return re.sub(r"^Abstract\s*", "", clean(w.get("abstract", "")))
    return ""


def pdf_link(item):
    """A direct PDF link the publisher registered with Crossref (works for many open-access papers)."""
    if not item.get("doi"):
        return ""
    w = json.loads(get("https://api.crossref.org/works/" + urllib.parse.quote(item["doi"]), timeout=30))["message"]
    for ln in w.get("link", []):
        if "pdf" in (ln.get("content-type") or "") and ln.get("URL"):
            return ln["URL"]
    return ""


def retag(cache_path=CACHE):
    """Re-sort the cached papers into the current topics (after the user edits them)."""
    conf = load_conf()
    compiled = compile_topics(conf["topics"])
    with open(cache_path) as fh:
        data = json.load(fh)
    for i in data.get("items", []):
        i["topics"] = [] if i.get("kind") == "news" else match(i["title"] + " " + i.get("abstract", ""), compiled)
    data["groups"] = {g: list(t) for g, t in conf["topics"].items()}
    data["journals"] = list(conf["journals"])
    data["news"] = news_groups(conf)
    with open(cache_path + ".tmp", "w") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(cache_path + ".tmp", cache_path)
    return data


def main():
    conf = load_conf()
    compiled = compile_topics(conf["topics"])
    try:
        with open(CACHE) as fh:
            old = json.load(fh)
    except (OSError, ValueError):
        old = {"items": []}
    old_ids = {i["id"] for i in old.get("items", [])}
    errors, fresh, arxiv_ok = {}, [], False
    today = dt.date.today().isoformat()
    from concurrent.futures import ThreadPoolExecutor

    def do_arxiv():
        last = old.get("arxiv_since")
        since = (dt.date.fromisoformat(last) - dt.timedelta(days=2)).isoformat() if last else \
            (dt.date.today() - dt.timedelta(days=KEEP_DAYS)).isoformat()   # first run: back-fill KEEP_DAYS
        return fetch_arxiv(conf["arxiv_categories"], since)

    def do_journal(name, spec):
        url, filt = spec[0], spec[1]
        keep = int(spec[4]) if len(spec) > 4 and spec[4] else KEEP_DAYS   # monthly journals: keep longer
        since_j = (dt.date.today() - dt.timedelta(days=keep)).isoformat()
        got = fetch_crossref(name, spec[2], since_j) if url == "crossref" else fetch_rss(name, url)
        oa = len(spec) > 3 and bool(spec[3])
        for g in got:
            g["filtered"] = bool(filt)
            if oa:
                g["oa"] = True
            if keep != KEEP_DAYS:
                g["keep"] = keep
        return got

    jsince = (dt.date.today() - dt.timedelta(days=KEEP_DAYS)).isoformat()
    old_img = {i["url"]: i["image"] for i in old.get("items", []) if i.get("image")}

    def do_news(name, url, group):
        got = [g for g in fetch_rss(name, url) if g["date"] >= jsince][:60]
        for g in got:
            g.update(kind="news", group=group, abstract=g["abstract"][:1500])
            if not g.get("image"):   # no picture in the feed: the article's preview image (once)
                g["image"] = old_img.get(g["url"]) or (lambda u: (lambda: og_image(u)))(g["url"])
        return got

    # everything in parallel (8 at a time): a few seconds instead of minutes
    with ThreadPoolExecutor(max_workers=8) as pool:
        futs = {pool.submit(do_arxiv): "arXiv"}
        for name, spec in conf["journals"].items():
            futs[pool.submit(do_journal, name, spec)] = name
        for name, (url, group) in conf.get("news", {}).items():
            futs[pool.submit(do_news, name, url, group)] = name
        for f, name in futs.items():
            try:
                fresh += f.result()
                if name == "arXiv":
                    arxiv_ok = True
            except Exception as e:
                errors[name] = str(e)[:120]
        # preview pictures for news, also in parallel
        lazy = [g for g in fresh if callable(g.get("image"))]
        for g, img in zip(lazy, pool.map(lambda g: _safe(g["image"]), lazy)):
            g["image"] = img
            if not img:
                g.pop("image")
    by_id = {i["id"]: i for i in old.get("items", [])}
    jdoi = {i["doi"].lower(): i["id"] for i in by_id.values() if i.get("doi") and i["kind"] == "journal"}
    for it in fresh:
        d = (it.get("doi") or "").lower()
        if it["kind"] == "journal" and d and d in jdoi and jdoi[d] != it["id"]:
            by_id.pop(jdoi[d], None)   # same paper listed before under another id (old RSS entry): keep the new one
        if it["kind"] == "journal" and d:
            jdoi[d] = it["id"]
        it["topics"] = [] if it["kind"] == "news" else match(it["title"] + " " + it.get("abstract", ""), compiled)
        if it.get("filtered") and not it["topics"]:
            continue
        prev = by_id.get(it["id"])
        it["first_seen"] = prev.get("first_seen", today) if prev else today
        by_id[it["id"]] = it
    cutoff = (dt.date.today() - dt.timedelta(days=KEEP_DAYS)).isoformat()
    short = (dt.date.today() - dt.timedelta(days=OTHER_DAYS)).isoformat()
    for i in by_id.values():   # re-tag old items too, so edited topics apply at once
        i["topics"] = [] if i["kind"] == "news" else match(i["title"] + " " + i.get("abstract", ""), compiled)
    def limit(i):
        if i.get("keep"):
            return (dt.date.today() - dt.timedelta(days=int(i["keep"]))).isoformat()
        return cutoff if (i["topics"] or i["kind"] != "arxiv") else short
    items = [i for i in by_id.values() if (i.get("date") or i.get("first_seen", today)) >= limit(i)]
    items.sort(key=lambda i: (i.get("date", ""), i.get("first_seen", "")), reverse=True)
    data = {"updated": time.strftime("%Y-%m-%d %H:%M"), "errors": errors, "items": items,
            "arxiv_since": today if arxiv_ok else old.get("arxiv_since"),
            "groups": {g: list(t) for g, t in conf["topics"].items()}, "journals": list(conf["journals"]),
            "news": news_groups(conf)}
    tmp = CACHE + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, CACHE)

    # short list for the top-bar menu: newest topic hits first
    hits = [i for i in items if i["topics"]]
    menu = [{"id": i["id"], "title": i["title"], "authors": i["authors"][:4],
             "category": i.get("category") or i["source"], "hits": i["topics"], "url": i["url"]}
            for i in hits[:40]]
    with open(MENU_CACHE + ".tmp", "w") as fh:
        json.dump({"updated": data["updated"], "papers": menu}, fh, ensure_ascii=False)
    os.replace(MENU_CACHE + ".tmp", MENU_CACHE)

    new_hits = [i for i in hits if i["id"] not in old_ids]
    if new_hits and old_ids and "--quiet" not in sys.argv and shutil.which("notify-send"):
        subprocess.run(["notify-send", "-a", "Physics Feed", "-i", "physics-feed",
                        f"{len(new_hits)} new papers in your topics",
                        "\n".join("• " + i["title"][:90] for i in new_hits[:4])])
    print(f"{len(items)} papers, {len(hits)} in your topics, errors: {list(errors) or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
