#!/usr/bin/env bash
# Installs the JDCM fetcher on a Raspberry Pi (Raspberry Pi OS, 32 or 64 bit) or any Debian-like
# Linux, for the user running it (no root, except apt and, if needed, linger):
#
#   curl -fsSLO https://raw.githubusercontent.com/olemoudi/jdcm/main/pwa/pi/install.sh && bash install.sh
#
# It installs curl, jq and git; puts jdcm-fetch.sh in ~/.local/share/jdcm-fetch; creates a deploy
# key for olemoudi/jdcm-data and says how to add it; installs a systemd user timer (07:07, 10:07,
# 13:07, 16:07, 19:07, Madrid) that runs without anyone logged in; and does a first run.
# Run it again to update the scripts: the key, the logs and the timer stay.
# Options: --no-apt (do not install packages), --no-timer, --yes (do not wait for the key), --no-run.
set -euo pipefail

RAW=${JDCM_RAW_BASE:-https://raw.githubusercontent.com/olemoudi/jdcm/main/pwa/pi}
DATA_REMOTE=${JDCM_DATA_REMOTE:-git@github.com:olemoudi/jdcm-data.git}
HOME_DIR=${XDG_DATA_HOME:-$HOME/.local/share}/jdcm-fetch
CONFIG_DIR=${XDG_CONFIG_HOME:-$HOME/.config}/jdcm-fetch
UNIT_DIR=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
APT=1 TIMER=1 WAIT=1 RUN=1
for a in "$@"; do
  case "$a" in
    --no-apt) APT=0 ;; --no-timer) TIMER=0 ;; --yes) WAIT=0 ;; --no-run) RUN=0 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
[[ $(id -u) != 0 ]] || { echo "Run it as your normal user (not root, not sudo): it asks for sudo when it needs it." >&2; exit 1; }

say "1/6 Packages (curl, jq, git)"
missing=()
for t in curl jq git ssh ssh-keygen flock; do command -v "$t" >/dev/null || missing+=("$t"); done
if (( ${#missing[@]} )); then
  if (( APT )); then
    sudo apt-get update -q
    sudo apt-get install -y -q curl jq git ca-certificates openssh-client util-linux
  else
    echo "Missing: ${missing[*]}. Install them with: sudo apt install curl jq git ca-certificates openssh-client" >&2
    exit 1
  fi
fi
echo "ok: $(uname -m), $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown OS}")"

say "2/6 The fetcher"
mkdir -p "$HOME_DIR"
for f in jdcm-fetch.sh jdcm-fetch.service jdcm-fetch.timer; do
  curl -fsS --max-time 60 -o "$HOME_DIR/$f.new" "$RAW/$f"
done
bash -n "$HOME_DIR/jdcm-fetch.sh.new"
for f in jdcm-fetch.sh jdcm-fetch.service jdcm-fetch.timer; do mv -f "$HOME_DIR/$f.new" "$HOME_DIR/$f"; done
chmod +x "$HOME_DIR/jdcm-fetch.sh"
echo "ok: $HOME_DIR/jdcm-fetch.sh"

say "3/6 The deploy key (it can write to olemoudi/jdcm-data and nothing else)"
mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"
if [[ ! -f $CONFIG_DIR/deploy_key ]]; then
  ssh-keygen -q -t ed25519 -N "" -C "jdcm-fetch@$(hostname)" -f "$CONFIG_DIR/deploy_key"
fi
# GitHub's host keys, from its API (over HTTPS), so the first connection is not a blind trust; if the
# API cannot be reached (it allows 60 calls an hour per address), GitHub's published ed25519 key.
GITHUB_ED25519="github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
if curl -fsS --max-time 30 https://api.github.com/meta 2>/dev/null | jq -r '.ssh_keys[] | "github.com " + .' >"$CONFIG_DIR/known_hosts.new" 2>/dev/null \
  && grep -qF "$GITHUB_ED25519" "$CONFIG_DIR/known_hosts.new"; then
  mv -f "$CONFIG_DIR/known_hosts.new" "$CONFIG_DIR/known_hosts"
else
  rm -f "$CONFIG_DIR/known_hosts.new"
  printf '%s\n' "$GITHUB_ED25519" >"$CONFIG_DIR/known_hosts"
fi
key_ok() {
  local check=yes
  [[ -s $CONFIG_DIR/known_hosts ]] || check=accept-new
  GIT_SSH_COMMAND="ssh -i $CONFIG_DIR/deploy_key -o IdentitiesOnly=yes -o BatchMode=yes -o UserKnownHostsFile=$CONFIG_DIR/known_hosts -o StrictHostKeyChecking=$check" \
    git ls-remote "$DATA_REMOTE" >/dev/null 2>&1
}
if key_ok; then
  echo "ok: the key already works"
else
  cat <<MSG

Add this key to the repository olemoudi/jdcm-data, with write access:

$(cat "$CONFIG_DIR/deploy_key.pub")

  On the web: https://github.com/olemoudi/jdcm-data/settings/keys → "Add deploy key" →
  any title (e.g. "Raspberry Pi"), paste the line above, tick "Allow write access" → "Add key".
  Or from a computer with gh:  gh repo deploy-key add <file with the line> --allow-write -t "Raspberry Pi" -R olemoudi/jdcm-data
MSG
  if (( WAIT )); then
    read -r -p "Press Enter once it is added... " _ </dev/tty
    if key_ok; then echo "ok: the key works"; else echo "The key does not work yet: check it and run install.sh again." >&2; exit 1; fi
  fi
fi

say "4/6 The timer (07:07, 10:07, 13:07, 16:07, 19:07, Madrid time)"
if (( TIMER )); then
  mkdir -p "$UNIT_DIR"
  cp "$HOME_DIR/jdcm-fetch.service" "$HOME_DIR/jdcm-fetch.timer" "$UNIT_DIR/"
  systemctl --user daemon-reload
  systemctl --user enable --now jdcm-fetch.timer
  # Linger: the user's timers run with nobody logged in (after a power cut, too).
  if [[ $(loginctl show-user "$USER" -p Linger --value 2>/dev/null) != yes ]]; then
    loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER"
  fi
  systemctl --user list-timers jdcm-fetch.timer --no-pager | head -3
else
  echo "(skipped: --no-timer)"
fi

say "5/6 A first run"
if (( RUN )); then
  if "$HOME_DIR/jdcm-fetch.sh"; then echo "ok"; else echo "The first run failed: see the lines above, or the log below." >&2; fi
  hour=$(TZ=Europe/Madrid date +%H)
  if (( 10#$hour < 7 || 10#$hour > 21 )); then echo "(It is night in Madrid: the first real run will be at 07:07. To try now: $HOME_DIR/jdcm-fetch.sh --now)"; fi
else
  echo "(skipped: --no-run)"
fi

say "6/6 Done"
cat <<MSG
  Log:          tail -n 40 ~/.local/state/jdcm-fetch/fetch.log
  Last run:     cat ~/.local/state/jdcm-fetch/last-run
  Next runs:    systemctl --user list-timers jdcm-fetch.timer
  Run now:      $HOME_DIR/jdcm-fetch.sh --now
MSG
