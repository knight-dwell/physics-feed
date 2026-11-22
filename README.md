# 〰 Wavepacket — Physics Feed

New physics papers and science news, sorted into **your** research topics, in one calm desktop app for Linux.

- **Feed**: every day it collects new papers from **arXiv** and **~90 journals** (all APS journals, Nature family,
  Science, PNAS, IOP, AIP, Springer, Elsevier, Optica, ACS, Wiley, SciPost, Quantum, Indian journals such as
  Pramana and Resonance…) and tags them with your topics: strongly correlated, topology, DMFT, soft matter,
  stat mech, photonics, … whatever you set up.
- **Read in the app**: full abstracts, arXiv PDFs and open-access papers open right next to the list; journal pages
  open there too (your institute's access works).
- **Search** all of arXiv, all journals (via Crossref) or Google Scholar, by words, author, ID/DOI, category and topic.
- **Cite** (BibTeX / RIS: copy, save, add to your `.bib`), **Share** (link, email, WhatsApp, Telegram, X, Bluesky,
  LinkedIn), **Save** into folders with your own `#flair` tags.
- **News**: ML & AI (Google, DeepMind, OpenAI, Hugging Face, …), physics news (APS Physics, Physics World, Quanta,
  …), Indian science news and PIB/DST press releases, kept apart from the papers.
- Dark and light mode, works in small tiled windows, no account, no tracking.

## Install

```bash
git clone https://github.com/knight-dwell/physics-feed.git
cd physics-feed
./install.sh
```

The installer detects your distribution (Ubuntu/Debian/Mint/Pop!_OS, Fedora, Arch/Manjaro, openSUSE, Alpine, Void),
installs the few system packages it needs (Python + GTK, plus the optional PDF and web viewers), installs the app for
your user only, adds it to the app menu and sets up a fetch twice a day.

Then open **Wavepacket** from the app menu, or type **`pfeed`** in a terminal. A welcome screen asks for your field(s).

| Command | What it does |
|---|---|
| `pfeed` | open the app |
| `pfeed --refresh` | fetch new papers now |
| `git pull && ./install.sh` | update (in the `physics-feed` folder) |
| `./uninstall.sh` | remove (add `--purge` to delete settings and saved papers too) |

## Android

Download **`wavepacket.apk`** from the [latest release](https://github.com/knight-dwell/physics-feed/releases/latest)
on your phone, open it and allow installing from your browser/files app when Android asks. Same topics, journals,
search, abstracts, PDFs, citations, folders and flairs, plus Android's share sheet and a notification when new papers
in your topics arrive. Settings can be sent between phone and desktop (⋮ → Export / Import settings).

Build it yourself: `cd android && ./gradlew assembleRelease` (needs JDK 17 and the Android SDK).

## Updates

- **Desktop**: the ⇪ button in the top bar checks GitHub for a new version; with **Update automatically** on, it
  installs new versions by itself (restart the app afterwards). Or: `git pull && ./install.sh`.
- **Android**: ⋮ → **Updates**; with **Update automatically** on, a new release is downloaded and Android asks you to
  confirm the install.

## Make it yours

- **My topics → ＋ Add a field…** pick from condensed matter theory / experiment, superconductivity, magnetism,
  2D materials, soft matter, statistical mechanics, AMO, quantum information, photonics, materials & computation,
  ML for physics, mathematical physics, high energy, and tick the tags you want. Keywords are editable:
  comma separated, short ALL-CAPS words (DMFT, SSH) match whole words, `a&b` needs both.
- **Journals & news** tick journals and news feeds, or **＋ Add your own** with an RSS link or the journal's ISSN
  (the ISSN works for any publisher).
- Press **?** in the app for the full guide.

## Where things live

- Settings: `~/.config/physics-feed/config.json`
- Saved papers, folders, flairs: `~/.local/share/physics-feed/state.json`
- Paper cache and downloaded PDFs: `~/.cache/physics-feed/`

Physics Feed talks only to arXiv, Crossref and the publishers' public feeds you choose.

## License

MIT
