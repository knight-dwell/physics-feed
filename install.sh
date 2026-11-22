#!/usr/bin/env bash
# Physics Feed installer: works on most Linux systems (Debian/Ubuntu/Mint/Pop, Fedora/RHEL,
# Arch/Manjaro/EndeavourOS, openSUSE, Alpine, Void). Installs for your user only; asks for your
# password once, only to install missing system packages.
#
#   ./install.sh            install or update
#   ./install.sh --no-deps  skip system packages (you install them yourself)
#   ./install.sh --yes      don't ask questions
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$HOME/.local/share/physics-feed/app"
BIN="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
FONT_DIR="$HOME/.local/share/fonts/physics-feed"
DEPS=1; YES=0
for a in "$@"; do
  case "$a" in
    --no-deps) DEPS=0 ;;
    --yes|-y) YES=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option: $a (see --help)"; exit 1 ;;
  esac
done

b=$'\e[1m'; d=$'\e[2m'; c=$'\e[38;5;153m'; g=$'\e[38;5;114m'; y=$'\e[38;5;222m'; r=$'\e[38;5;210m'; n=$'\e[0m'
step() { printf '\n%s==>%s %s%s%s\n' "$c" "$n" "$b" "$*" "$n"; }
ok()   { printf '   %s✓%s %s\n' "$g" "$n" "$*"; }
warn() { printf '   %s!%s %s\n' "$y" "$n" "$*"; }
die()  { printf '\n%s✗ %s%s\n' "$r" "$*" "$n"; exit 1; }

[[ $EUID -eq 0 ]] && die "Run this as your normal user (not with sudo); it asks for the password when needed."
[[ -f "$REPO/physics_feed/app.py" ]] || die "Run it from the physics-feed folder (where install.sh is)."

cat <<EOF
${c}${b}
   〰  Wavepacket (Physics Feed) — installer${n}
   ${d}new physics papers and news, sorted into your research topics${n}
EOF

# ---------------------------------------------------------------- 1. system packages
SUDO=""; command -v sudo >/dev/null && SUDO="sudo"
have_py_gtk() {
  python3 - <<'PY' 2>/dev/null
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk
PY
}
pm_install() {   # pm_install <required...> -- <optional...>
  local req=() opt=() mode=req
  for p in "$@"; do [[ $p == -- ]] && { mode=opt; continue; }; [[ $mode == req ]] && req+=("$p") || opt+=("$p"); done
  case "$PM" in
    apt)    $SUDO apt-get update -qq || true
            $SUDO apt-get install -y "${req[@]}"
            for p in "${opt[@]}"; do $SUDO apt-get install -y "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
    dnf)    $SUDO dnf install -y "${req[@]}"
            for p in "${opt[@]}"; do $SUDO dnf install -y "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
    pacman) $SUDO pacman -S --needed --noconfirm "${req[@]}"
            for p in "${opt[@]}"; do $SUDO pacman -S --needed --noconfirm "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
    zypper) $SUDO zypper --non-interactive install "${req[@]}"
            for p in "${opt[@]}"; do $SUDO zypper --non-interactive install "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
    apk)    $SUDO apk add "${req[@]}"
            for p in "${opt[@]}"; do $SUDO apk add "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
    xbps)   $SUDO xbps-install -Sy "${req[@]}"
            for p in "${opt[@]}"; do $SUDO xbps-install -y "$p" >/dev/null 2>&1 && ok "$p" || warn "optional package $p not available"; done ;;
  esac
}

if [[ $DEPS == 1 ]]; then
  step "Installing what Physics Feed needs (Python + GTK; PDF and web viewers)"
  PM=""
  for x in apt-get:apt dnf:dnf pacman:pacman zypper:zypper apk:apk xbps-install:xbps; do
    command -v "${x%%:*}" >/dev/null && { PM="${x##*:}"; break; }
  done
  if [[ -z $PM ]]; then
    warn "Couldn't recognise your package manager. Install these yourself, then run ./install.sh --no-deps :"
    warn "  Python 3, PyGObject (python3-gi), GTK 3, WebKit2GTK, Evince (libevview), libnotify"
  else
    ok "package manager: $PM"
    [[ -z $SUDO ]] && warn "sudo not found: package installation may fail (run as a user with sudo)"
    case "$PM" in
      apt)    pm_install python3 python3-gi gir1.2-gtk-3.0 -- gir1.2-webkit2-4.1 gir1.2-webkit2-4.0 gir1.2-evince-3.0 \
                         libnotify-bin fonts-jetbrains-mono curl ;;
      dnf)    pm_install python3 python3-gobject gtk3 -- webkit2gtk4.1 webkit2gtk3 evince-libs libnotify jetbrains-mono-fonts curl ;;
      pacman) pm_install python python-gobject gtk3 -- webkit2gtk-4.1 webkit2gtk evince libnotify ttf-jetbrains-mono curl ;;
      zypper) pm_install python3 python3-gobject python3-gobject-Gdk typelib-1_0-Gtk-3_0 -- typelib-1_0-WebKit2-4_1 \
                         typelib-1_0-WebKit2-4_0 typelib-1_0-EvinceView-3_0 typelib-1_0-EvinceDocument-3_0 libnotify-tools \
                         jetbrains-mono-fonts curl ;;
      apk)    pm_install python3 py3-gobject3 gtk+3.0 -- webkit2gtk-4.1 webkit2gtk evince libnotify font-jetbrains-mono curl ;;
      xbps)   pm_install python3 python3-gobject gtk+3 -- libwebkit2gtk41 libwebkit2gtk evince libnotify curl ;;
    esac
  fi
fi
have_py_gtk || die "Python can't load GTK 3 (PyGObject). Install python3-gi / python3-gobject and run ./install.sh again."
ok "Python + GTK work"
python3 - <<'PY' && ok "PDF preview available" || warn "PDF preview not available (install Evince); PDFs will open in your viewer"
import gi
gi.require_version("EvinceView", "3.0")
from gi.repository import EvinceView
PY
python3 - <<'PY' && ok "journal-page preview available" || warn "web preview not available (install WebKit2GTK); pages open in the browser"
import gi
try:
    gi.require_version("WebKit2", "4.1")
except ValueError:
    gi.require_version("WebKit2", "4.0")
from gi.repository import WebKit2
PY

# ---------------------------------------------------------------- 2. icon font
step "Fonts (icons in the app)"
if fc-list 2>/dev/null | grep -i "Nerd Font" >/dev/null; then   # (grep -q + pipefail = false alarm)
  ok "a Nerd Font is already installed"
else
  mkdir -p "$FONT_DIR"
  url="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/NerdFontsSymbolsOnly.tar.xz"
  tmp="$(mktemp -d)"
  if { command -v curl >/dev/null && curl -fsSL "$url" -o "$tmp/f.tar.xz"; } || \
     { command -v wget >/dev/null && wget -q "$url" -O "$tmp/f.tar.xz"; }; then
    tar -xJf "$tmp/f.tar.xz" -C "$FONT_DIR" --wildcards '*.ttf' 2>/dev/null || tar -xJf "$tmp/f.tar.xz" -C "$FONT_DIR"
    fc-cache -f "$FONT_DIR" >/dev/null 2>&1 || true
    ok "icon font installed (Symbols Nerd Font)"
  else
    warn "couldn't download the icon font; some icons may show as boxes"
  fi
  rm -rf "$tmp"
fi

# ---------------------------------------------------------------- 3. the app itself
step "Installing Physics Feed for $USER"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR" "$BIN" "$DESKTOP_DIR" "$ICON_DIR"
cp -r "$REPO/physics_feed" "$APP_DIR/"
find "$APP_DIR" -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
# remember which version this is, for the in-app updater
COMMIT="${PF_COMMIT:-$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo "")}"
printf '{"commit": "%s", "date": "%s"}\n' "$COMMIT" "$(date +%F)" > "$APP_DIR/physics_feed/INSTALLED.json"
cat > "$BIN/physics-feed" <<EOF
#!/bin/sh
# Physics Feed launcher (installed by physics-feed/install.sh)
PYTHONPATH="$APP_DIR\${PYTHONPATH:+:\$PYTHONPATH}" exec python3 -m physics_feed "\$@"
EOF
chmod +x "$BIN/physics-feed"
ln -sf "$BIN/physics-feed" "$BIN/pfeed"
ok "commands: physics-feed  and the short one  pfeed"
install -m 644 "$REPO/data/physics-feed.svg" "$ICON_DIR/physics-feed.svg"
cat > "$DESKTOP_DIR/physics-feed.desktop" <<EOF
[Desktop Entry]
Name=Wavepacket
GenericName=Physics paper feed
Comment=New physics papers and news, sorted into your research topics
Exec=$BIN/physics-feed
Icon=physics-feed
Terminal=false
Type=Application
Categories=Education;Science;Physics;
Keywords=arxiv;papers;journals;physics;research;
StartupWMClass=physics-feed
EOF
command -v update-desktop-database >/dev/null && update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" >/dev/null 2>&1 || true
ok "app menu entry: Physics Feed"

# ---------------------------------------------------------------- 4. fetch twice a day
step "Fetching new papers twice a day (08:30 and 20:30)"
if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  U="$HOME/.config/systemd/user"; mkdir -p "$U"
  cat > "$U/physics-feed.service" <<EOF
[Unit]
Description=Physics Feed: fetch new papers
After=network-online.target
[Service]
Type=oneshot
ExecStart=$BIN/physics-feed --refresh
EOF
  cat > "$U/physics-feed.timer" <<EOF
[Unit]
Description=Physics Feed: fetch new papers twice a day
[Timer]
OnCalendar=*-*-* 08,20:30:00
OnStartupSec=3min
Persistent=true
[Install]
WantedBy=timers.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable physics-feed.timer >/dev/null 2>&1 && systemctl --user restart physics-feed.timer \
    && ok "systemd user timer on" || warn "couldn't start the timer"
elif command -v crontab >/dev/null; then
  ( crontab -l 2>/dev/null | grep -v 'physics-feed --refresh'; echo "30 8,20 * * * $BIN/physics-feed --refresh" ) | crontab -
  ok "cron job added"
else
  warn "no systemd or cron: press ⟳ in the app to fetch (it also fetches when you open it the first time)"
fi

# ---------------------------------------------------------------- 5. PATH
if [[ ":$PATH:" != *":$BIN:"* ]]; then
  for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
    [[ -f $rc ]] || continue
    grep -q 'physics-feed: ~/.local/bin' "$rc" || printf '\n# physics-feed: ~/.local/bin on PATH\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$rc"
  done
  NEWTERM=1
fi
if [[ -n ${FISH_VERSION:-} ]] || command -v fish >/dev/null; then
  mkdir -p "$HOME/.config/fish/conf.d"
  echo 'fish_add_path -g $HOME/.local/bin' > "$HOME/.config/fish/conf.d/physics-feed.fish"
fi

# ---------------------------------------------------------------- done
cat <<EOF

${g}${b}  ✓ Wavepacket is installed.${n}

  ${b}Open it${n}
    • from your app menu:      ${c}Wavepacket${n}
    • or in a terminal type:   ${c}pfeed${n}${NEWTERM:+   ${d}(open a new terminal first)${n}}

  ${b}First start${n}
    A welcome screen asks for your research field(s); papers arrive within a minute.
    Press ${c}?${n} in the app for a short guide.

  ${b}Handy commands${n}
    ${c}pfeed --refresh${n}     fetch new papers now
    ${c}pfeed --help${n}        all options
    ${c}cd $(basename "$REPO") && git pull && ./install.sh${n}   update
    ${c}./uninstall.sh${n}      remove it   ${d}(your settings stay unless you add --purge)${n}

  ${d}Settings: ~/.config/physics-feed   Saved papers: ~/.local/share/physics-feed${n}

EOF
