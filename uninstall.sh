#!/usr/bin/env bash
# Remove Physics Feed.   ./uninstall.sh          keeps your settings and saved papers
#                        ./uninstall.sh --purge  also deletes them
set -uo pipefail
PURGE=0; [[ ${1:-} == --purge ]] && PURGE=1
g=$'\e[38;5;114m'; y=$'\e[38;5;222m'; n=$'\e[0m'

systemctl --user disable --now physics-feed.timer >/dev/null 2>&1
rm -f "$HOME/.config/systemd/user/physics-feed.service" "$HOME/.config/systemd/user/physics-feed.timer"
systemctl --user daemon-reload >/dev/null 2>&1
command -v crontab >/dev/null && crontab -l 2>/dev/null | grep -q 'physics-feed --refresh' && \
  crontab -l 2>/dev/null | grep -v 'physics-feed --refresh' | crontab -
rm -rf "$HOME/.local/share/physics-feed/app"
rm -f "$HOME/.local/bin/physics-feed" "$HOME/.local/bin/pfeed" \
      "$HOME/.local/share/applications/physics-feed.desktop" \
      "$HOME/.local/share/icons/hicolor/scalable/apps/physics-feed.svg" \
      "$HOME/.config/fish/conf.d/physics-feed.fish"
rm -rf "$HOME/.local/share/fonts/physics-feed"
if [[ $PURGE == 1 ]]; then
  read -r -p "Delete your Physics Feed settings, saved papers and folders too? [y/N] " a
  if [[ $a == [yY]* ]]; then
    rm -rf "$HOME/.config/physics-feed" "$HOME/.cache/physics-feed" "$HOME/.local/share/physics-feed"
    echo "${y}settings and saved papers deleted${n}"
  fi
fi
echo "${g}✓ Physics Feed removed.${n} (The PATH line in your shell rc file is harmless; delete it if you like.)"
