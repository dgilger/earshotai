#!/usr/bin/env bash
# Runs as root, started by systemd only (earshot-updater@.service). The web portal cannot run
# commands; it can only ask by writing "check" or "update" to $DATA_DIR/update-request.
#   earshot-updater.sh check     look on GitHub for a newer version, write update-status.json
#   earshot-updater.sh request   do what update-request asks, then remove it
set -uo pipefail
APP_DIR=${EARSHOT_APP_DIR:-/opt/earshot}
DATA_DIR=${EARSHOT_DATA_DIR:-/var/lib/earshot}
CONF_DIR=${EARSHOT_CONF_DIR:-/etc/earshot}
BRANCH=main
REQ=$DATA_DIR/update-request
STATUS=$DATA_DIR/update-status.json
LOG=$DATA_DIR/update.log
SVC_USER=earshot

write_status() {   # write_status <state> [message]
  local cur latest notes
  cur=$(git -C "$APP_DIR" log -1 --format='%h %cs' 2>/dev/null)
  latest=$(git -C "$APP_DIR" log -1 --format='%h %cs' FETCH_HEAD 2>/dev/null || echo "")
  # Update notes: the commit titles that are new, newest first (no merge commits).
  notes=$(git -C "$APP_DIR" log --no-merges -n 30 --format='%cs%x09%s' HEAD..FETCH_HEAD 2>/dev/null || true)
  STATE="$1" MSG="${2:-}" CUR="$cur" LATEST="$latest" NOTES="$notes" python3 - "$STATUS" <<'PY'
import json, os, sys, time
notes = [dict(zip(("date", "text"), l.split("\t", 1))) for l in os.environ["NOTES"].splitlines() if "\t" in l]
s = {"state": os.environ["STATE"], "message": os.environ["MSG"], "current": os.environ["CUR"],
     "latest": os.environ["LATEST"], "available": len(notes) > 0, "notes": notes, "checked": int(time.time())}
tmp = sys.argv[1] + ".tmp"
with open(tmp, "w") as f:
    json.dump(s, f)
os.replace(tmp, sys.argv[1])
PY
  chown "$SVC_USER" "$STATUS" 2>/dev/null; chmod 640 "$STATUS"
}

fetch() {
  GIT_TERMINAL_PROMPT=0 git -C "$APP_DIR" fetch -q origin "$BRANCH" 2>/dev/null && return 0
  # Private repository: use the GitHub token the installer saved (never written to the log).
  local tok url
  tok=$(cat "$CONF_DIR/github_token" 2>/dev/null) || return 1
  [ -n "$tok" ] || return 1
  url=$(git -C "$APP_DIR" remote get-url origin)
  GIT_TERMINAL_PROMPT=0 git -C "$APP_DIR" fetch -q "${url/https:\/\//https:\/\/x-access-token:$tok@}" "$BRANCH" 2>/dev/null
}

check() {
  if fetch; then write_status idle
  else write_status error "Could not reach GitHub. If the project is private, run once on the server: sudo earshot update"
  fi
}

update() {
  write_status updating "Updating…"
  : > "$LOG"; chown "$SVC_USER" "$LOG"; chmod 640 "$LOG"
  # The installer prints its steps; the portal shows them. It restarts Earshot at the end.
  if bash "$APP_DIR/server/install.sh" --update < /dev/null 2>&1 | sed -E 's/\x1b\[[0-9;]*[A-Za-z]//g; s#(https://)[^/@ ]+@#\1***@#g' >> "$LOG"; then
    fetch; write_status done "Updated"
  else
    fetch; write_status error "The update did not finish. See the steps below, or run on the server: sudo earshot update"
  fi
}

case "${1:-}" in
  check) check ;;
  request)
    what=$(head -c 16 "$REQ" 2>/dev/null | tr -cd 'a-z')
    rm -f "$REQ"
    case "$what" in
      update) update ;;
      *) check ;;
    esac ;;
  *) echo "usage: $0 check|request" >&2; exit 2 ;;
esac
