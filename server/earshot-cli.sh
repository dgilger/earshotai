#!/usr/bin/env bash
# 'earshot' command, installed to /usr/local/bin/earshot by the installer.
APP_DIR=/opt/earshot
ENV_FILE=/etc/earshot/earshot.env
PORT=$(grep -oP '^EARSHOT_PORT=\K.*' "$ENV_FILE" 2>/dev/null || echo 8000)

if [ "$(id -u)" -ne 0 ]; then
  command -v sudo > /dev/null && exec sudo "$0" "$@"
  echo "Run this as root (or with sudo)."; exit 1
fi

py() { (cd / && runuser -u earshot -- env HOME=/var/lib/earshot $(grep -v '^#' "$ENV_FILE" | xargs) PYTHONPATH="$APP_DIR/server" "$APP_DIR/venv/bin/python" "$@"); }

case "${1:-help}" in
  status)
    if systemctl is-active --quiet earshot; then echo "Earshot is running."; else echo "Earshot is NOT running. Start it: earshot restart"; fi
    curl -fsS --max-time 5 -H "Authorization: Bearer $(cat /var/lib/earshot/token)" "http://127.0.0.1:$PORT/api/status" \
      | python3 -c 'import json,sys
s=json.load(sys.stdin)
print(f"  State:       {s[\"state\"]}" + (f"  ({s[\"error\"]})" if s["error"] else ""))
print(f"  Model:       {s[\"model\"]} on {\"GPU\" if s[\"device\"]==\"cuda\" else \"CPU\"} ({s[\"device_note\"]})")
print(f"  Waiting:     {s[\"queue\"]} pieces of audio" + (f", {s[\"failed\"]} failed" if s["failed"] else ""))
print(f"  Transcripts: {s[\"recordings\"]} ({s[\"audio_hours\"]} h of speech)")
print(f"  Address:     {s[\"url\"]}")
print(f"  Free disk:   {s[\"disk_free_gb\"]} GB")' 2>/dev/null
    ;;
  token)   py -m earshot.pair token ;;
  pair)    py -m earshot.pair "${2:-all}" ;;
  logs)    journalctl -u earshot -n "${2:-100}" -f ;;
  restart) systemctl restart earshot && echo "Restarted." ;;
  stop)    systemctl stop earshot && echo "Stopped. Start again: earshot restart" ;;
  remote)  bash "$APP_DIR/server/install.sh" --remote ;;
  update)  bash "$APP_DIR/server/install.sh" --update ;;
  new-token)
    read -r -p "Make a new access token? The phone and AI tools must be connected again. [y/N] " a
    [[ "${a,,}" == y* ]] || exit 0
    py -c "from earshot import config; print('New token:', config.new_token())" ;;
  uninstall) bash "$APP_DIR/server/install.sh" --uninstall ;;
  *)
    cat <<'H'
earshot status      is it running, what is it doing
earshot token       show the access token (for the web portal)
earshot pair        phone QR code and AI tool address (pair phone / pair ai)
earshot remote      set up access from outside your home (Cloudflare or Tailscale)
earshot update      get the newest version and restart
earshot logs        follow the server log (Ctrl+C to stop)
earshot restart     restart the server
earshot new-token   make a new access token
earshot uninstall   remove Earshot
H
    ;;
esac
