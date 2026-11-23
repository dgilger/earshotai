#!/usr/bin/env bash
# Earshot server installer.
#
# Sets up the Earshot transcription server on a Linux computer, step by step, and checks each
# step before it goes on. Safe to run again: it updates what is there and keeps your transcripts.
#
#   curl -fsSL https://raw.githubusercontent.com/dgilger/earshotai/main/server/install.sh | sudo bash
#
# or, from a copy of the code:   sudo bash server/install.sh
#
# Other uses (the 'earshot' command calls these):
#   install.sh --remote      only set up access from outside your home (Cloudflare or Tailscale)
#   install.sh --update      get the newest code, update, restart
#   install.sh --uninstall   remove Earshot (asks before it deletes transcripts)

set -uo pipefail

# ---- settings ---------------------------------------------------------------------------------
# Where the code comes from. Change these two lines if the project moves.
REPO_URL="${EARSHOT_REPO:-https://github.com/dgilger/earshotai.git}"
BRANCH="${EARSHOT_BRANCH:-main}"

APP_DIR=/opt/earshot              # code + Python environment
DATA_DIR=/var/lib/earshot         # transcripts, models, token
CONF_DIR=/etc/earshot
ENV_FILE=$CONF_DIR/earshot.env
PORT="${EARSHOT_PORT:-8000}"
SVC_USER=earshot
LOG=/var/log/earshot-install.log
TOTAL_STEPS=10

# ---- look -------------------------------------------------------------------------------------
if [ -t 1 ]; then
  B=$'\e[1m'; D=$'\e[2m'; G=$'\e[38;5;79m'; Y=$'\e[38;5;179m'; R=$'\e[38;5;203m'; N=$'\e[0m'
else
  B=""; D=""; G=""; Y=""; R=""; N=""
fi

step_no=0
step()  { step_no=$((step_no + 1)); echo; echo "${D}────────────────────────────────────────────────────────────${N}"; echo "${B}Step $step_no of $TOTAL_STEPS  ·  $1${N}"; echo; }
say()   { echo "  $*"; }
info()  { echo "  ${D}$*${N}"; }
ok()    { echo "  ${G}✓${N} $*"; }
warn()  { echo "  ${Y}!${N} $*"; }
bad()   { echo "  ${R}✗${N} $*"; }
die()   {
  echo; bad "$1"
  [ -n "${2:-}" ] && say "  What to do: $2"
  say "  The full log is in $LOG (show it with: sudo tail -50 $LOG)"
  say "  You can run this installer again at any time. It continues where it stopped."
  exit 1
}

# Read answers from the keyboard even when the script comes through a pipe (curl | bash).
TTY=/dev/tty
[ -r $TTY ] || TTY=/dev/stdin
pause() { echo; read -r -p "  ${D}Press Enter when you are ready to continue…${N} " _ < $TTY; echo; }
ask() {   # ask "question" default(y/n) -> returns 0 for yes
  local q=$1 def=${2:-y} a hint
  [ "$def" = y ] && hint="[Y/n]" || hint="[y/N]"
  while true; do
    read -r -p "  $q $hint " a < $TTY
    a=${a:-$def}
    case "${a,,}" in y|yes) return 0 ;; n|no) return 1 ;; esac
    say "Please type y or n."
  done
}
choose() {  # choose "prompt" n -> echo number 1..n
  local a
  while true; do
    read -r -p "  $1 " a < $TTY
    if [[ "$a" =~ ^[0-9]+$ ]] && [ "$a" -ge 1 ] && [ "$a" -le "$2" ]; then echo "$a"; return; fi
    say "Please type a number from 1 to $2." > /dev/stderr
  done
}
run() {   # run "what" command… : runs quietly into the log, shows a spinner
  local what=$1; shift
  echo "=== $what ($1)" >> "$LOG"   # not the arguments: they can hold a token
  # Output goes to the log with tokens blanked out (GitHub tokens in URLs, Cloudflare tunnel tokens).
  ( "$@" 2>&1 | sed -E 's#(https://)[^/@ ]+@#\1***@#g; s/eyJ[A-Za-z0-9_=.-]{20,}/eyJ***/g' >> "$LOG"; exit "${PIPESTATUS[0]}" ) &
  local pid=$! i=0 s='|/-\'
  while kill -0 $pid 2>/dev/null; do
    [ -t 1 ] && printf "\r  %s %s " "${s:i++%4:1}" "$what"
    sleep 0.3
  done
  wait $pid; local rc=$?
  [ -t 1 ] && printf "\r\033[K"
  return $rc
}

py()  { runuser -u $SVC_USER -- env $(grep -v '^#' "$ENV_FILE" 2>/dev/null | xargs) PYTHONPATH="$APP_DIR/server" "$APP_DIR/venv/bin/python" "$@"; }
lan_ip() { hostname -I 2>/dev/null | awk '{print $1}'; }
public_url() { py -c "from earshot import config; print(config.load()['public_url'])" 2>/dev/null; }

need_root() {
  if [ "$(id -u)" -ne 0 ]; then
    if [ -f "$0" ] && command -v sudo > /dev/null; then
      echo "The installer needs administrator rights. Starting it again with sudo…"
      exec sudo -E bash "$0" "$@"
    fi
    echo "The installer needs administrator rights. Run it with sudo (or as root)."
    exit 1
  fi
  mkdir -p "$(dirname $LOG)"; touch "$LOG"; chmod 600 "$LOG"
  echo "==== $(date) earshot installer $*" >> "$LOG"
}

# ---- 1. check the computer --------------------------------------------------------------------
check_system() {
  step "Check this computer"
  [ "$(uname -s)" = Linux ] || die "This installer is for Linux only." "Use a Linux computer, a Linux virtual machine, or a Proxmox container."
  ok "Linux ($(uname -m))"

  . /etc/os-release 2>/dev/null || true
  if command -v apt-get > /dev/null; then
    ok "System: ${PRETTY_NAME:-Debian-like}"
  else
    die "This installer works with Debian, Ubuntu and similar systems (they have 'apt')." \
        "Install Ubuntu Server 24.04 or Debian 12 (both free), then run the installer again."
  fi

  case "$(uname -m)" in
    x86_64|aarch64) ;;
    *) die "This processor type ($(uname -m)) is not supported." "Use a 64-bit PC (x86_64) or a 64-bit ARM computer." ;;
  esac

  local mem_gb; mem_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
  if [ "$mem_gb" -lt 4 ]; then
    die "This computer has ${mem_gb} GB of memory. Earshot needs at least 4 GB (8 GB or more is better)." \
        "Give the virtual machine or container more memory, then run the installer again."
  elif [ "$mem_gb" -lt 8 ]; then
    warn "${mem_gb} GB of memory. It will work, but use a smaller speech model (the installer suggests one)."
  else
    ok "${mem_gb} GB of memory"
  fi

  mkdir -p /var/lib
  local free_gb; free_gb=$(df -P -BG /var/lib | awk 'NR==2 {gsub("G","",$4); print $4}')
  if [ "$free_gb" -lt 8 ]; then
    die "Only ${free_gb} GB of free disk space. Earshot needs about 8 GB (programs and speech models)." \
        "Make more disk space free, or give the virtual machine a bigger disk."
  fi
  ok "${free_gb} GB of free disk space"

  if curl -fsS --max-time 15 -o /dev/null https://github.com; then
    ok "Internet works"
  else
    die "Cannot reach github.com." "Check the internet connection of this computer (try: ping 1.1.1.1), then run the installer again."
  fi

  if command -v systemctl > /dev/null && [ -d /run/systemd/system ]; then
    ok "systemd is running (Earshot runs as a background service)"
  else
    die "This system does not use systemd, so Earshot cannot run as a service." "Use Ubuntu or Debian, which use systemd."
  fi

  local virt; virt=$(systemd-detect-virt -c 2>/dev/null || true)
  if [ -n "$virt" ] && [ "$virt" != none ]; then
    info "This is a container ($virt). That is fine. A GPU only works if it is passed through to the container."
  fi
}

# ---- 2. programs from the system --------------------------------------------------------------
install_packages() {
  step "Install the programs Earshot needs"
  say "Earshot needs: git (to get the code), ffmpeg (to read audio files), curl."
  export DEBIAN_FRONTEND=noninteractive
  run "Updating the package list" apt-get update -q || die "Could not update the package list." "Check the internet connection, then run the installer again."
  run "Installing git, ffmpeg, curl" apt-get install -y -q git curl ca-certificates ffmpeg python3 pciutils \
    || die "Could not install the programs." "Run 'sudo apt-get install git curl ffmpeg' by hand to see the problem."
  for c in git curl ffmpeg; do
    command -v $c > /dev/null && ok "$c" || die "$c did not install." "Run 'sudo apt-get install $c' and look at the message."
  done
}

# ---- 3. graphics card -------------------------------------------------------------------------
GPU=cpu
detect_gpu() {
  step "Look for a graphics card (GPU)"
  say "An NVIDIA graphics card makes transcription about 10 times faster. Without one, Earshot"
  say "uses the processor (CPU). That works too, it is just slower."
  echo
  if command -v nvidia-smi > /dev/null && nvidia-smi -L > /dev/null 2>&1; then
    local name mem
    name=$(nvidia-smi --query-gpu=name --format=csv,noheader | head -1)
    mem=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits | head -1)
    ok "Found: $name ($((mem / 1024)) GB video memory). The driver works."
    if [ "$mem" -lt 5000 ]; then
      warn "Less than 5 GB of video memory: the installer will suggest the 'large-v3-turbo' model."
    fi
    GPU=cuda
    return
  fi
  if lspci 2>/dev/null | grep -qi 'nvidia'; then
    warn "An NVIDIA card is in this computer, but its driver is not installed (or not working)."
    if command -v ubuntu-drivers > /dev/null; then
      say "Ubuntu can install the right driver for you. After that the computer must restart,"
      say "and then you run this installer again."
      if ask "Install the NVIDIA driver now?" y; then
        run "Installing the NVIDIA driver (this can take 5-10 minutes)" ubuntu-drivers install \
          || die "The driver did not install." "See https://ubuntu.com/server/docs/nvidia-drivers-installation"
        ok "Driver installed."
        say "${B}Restart the computer now (sudo reboot), then run the installer again.${N}"
        exit 0
      fi
    else
      say "To use it, install the NVIDIA driver first:"
      say "  Debian:  https://wiki.debian.org/NvidiaGraphicsDrivers"
      say "  Proxmox: install the driver on the Proxmox host, then pass the card to this VM or container."
      say "Then run this installer again. Or continue now with the CPU and switch later."
    fi
    ask "Continue now with the CPU?" y || exit 0
  else
    ok "No NVIDIA card found. Earshot will use the CPU."
  fi
  info "Speed on a CPU: about 1-3 minutes of work for 10 minutes of speech on a modern 8-core PC."
}

# ---- 4. code ----------------------------------------------------------------------------------
get_code() {
  step "Download Earshot"
  if [ -d "$APP_DIR/.git" ]; then
    say "Earshot is already here ($APP_DIR). Getting the newest version…"
    run "Updating the code" git -C "$APP_DIR" pull --ff-only origin "$BRANCH" \
      && ok "Code is up to date" \
      || warn "Could not update the code (no internet, or local changes). Using the version that is here."
    return
  fi
  rm -rf "$APP_DIR.tmp"
  if run "Downloading from GitHub" git clone --depth 1 -b "$BRANCH" "$REPO_URL" "$APP_DIR.tmp"; then
    mv "$APP_DIR.tmp" "$APP_DIR"
    ok "Downloaded to $APP_DIR"
    return
  fi
  warn "Could not download $REPO_URL"
  say "If the project is private on GitHub, you need a GitHub access token:"
  say "  1. Open https://github.com/settings/personal-access-tokens/new"
  say "  2. Name: earshot-server.  Repository access: Only select repositories → the Earshot repository."
  say "  3. Permissions → Contents: Read-only.  Click Generate token and copy it."
  echo
  local tok
  read -r -s -p "  Paste the token here (it does not show while you type), or press Enter to stop: " tok < $TTY; echo
  [ -n "$tok" ] || die "No code downloaded." "Check the address $REPO_URL in a browser."
  local authed=${REPO_URL/https:\/\//https:\/\/x-access-token:$tok@}
  run "Downloading from GitHub" git clone --depth 1 -b "$BRANCH" "$authed" "$APP_DIR.tmp" \
    || die "The download failed with this token." "Check that the token has access to the repository and 'Contents: Read'."
  git -C "$APP_DIR.tmp" remote set-url origin "$REPO_URL"   # don't keep the token on disk
  mv "$APP_DIR.tmp" "$APP_DIR"
  ok "Downloaded to $APP_DIR"
}

# ---- 5. Python --------------------------------------------------------------------------------
install_python() {
  step "Install the speech software (Python)"
  say "This puts Whisper (speech to text) and the speaker-label software in $APP_DIR/venv."
  say "It does not change the Python of your system. It can take 2-5 minutes."
  if ! command -v uv > /dev/null; then
    run "Installing uv (Python installer)" sh -c 'curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin UV_NO_MODIFY_PATH=1 sh' \
      || die "Could not install uv." "Check the internet connection and run the installer again."
  fi
  ok "uv $(uv --version 2>/dev/null | awk '{print $2}')"
  export UV_PYTHON_INSTALL_DIR=/opt/earshot-python
  if [ ! -x "$APP_DIR/venv/bin/python" ]; then
    run "Making a Python 3.12 environment" uv venv -q --python 3.12 "$APP_DIR/venv" \
      || die "Could not make the Python environment." "See the log."
  fi
  run "Installing the speech software" uv pip install -q --python "$APP_DIR/venv/bin/python" -r "$APP_DIR/server/requirements-server.txt" \
    || die "Could not install the speech software." "Check the internet connection and disk space, then run the installer again."
  if [ "$GPU" = cuda ]; then
    run "Installing the NVIDIA GPU libraries (about 1 GB)" uv pip install -q --python "$APP_DIR/venv/bin/python" \
      "nvidia-cublas-cu12" "nvidia-cudnn-cu12==9.*" \
      || { warn "Could not install the GPU libraries. Earshot will use the CPU."; GPU=cpu; }
  fi
  PYTHONPATH="$APP_DIR/server" "$APP_DIR/venv/bin/python" -c "import faster_whisper, sherpa_onnx, fastapi, mcp, earshot.main" >> "$LOG" 2>&1 \
    || die "The speech software is installed but does not start." "See the log; then run the installer again."
  ok "Speech software works"
}

# ---- 6. settings ------------------------------------------------------------------------------
configure() {
  step "Settings"
  id $SVC_USER > /dev/null 2>&1 || useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin $SVC_USER
  mkdir -p "$DATA_DIR" "$CONF_DIR"
  chown -R $SVC_USER: "$DATA_DIR"
  chmod 700 "$DATA_DIR"
  ok "Earshot runs as its own user '$SVC_USER'. Transcripts are kept in $DATA_DIR."

  local libs=""
  if [ "$GPU" = cuda ]; then
    libs=$(ls -d "$APP_DIR"/venv/lib/python3*/site-packages/nvidia/*/lib 2>/dev/null | paste -sd:)
  fi
  cat > "$ENV_FILE" <<EOF
# Earshot server settings. Change with care; restart afterwards: sudo systemctl restart earshot
EARSHOT_DATA=$DATA_DIR
EARSHOT_PORT=$PORT
HF_HUB_DISABLE_TELEMETRY=1
${libs:+LD_LIBRARY_PATH=$libs}
EOF
  chmod 640 "$ENV_FILE"; chgrp $SVC_USER "$ENV_FILE"

  local current; current=$(py -c "from earshot import config; print(config.load()['model'])" 2>/dev/null || echo auto)
  local cores mem_gb rec
  cores=$(nproc); mem_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
  if [ "$GPU" = cuda ]; then rec=1
  elif [ "$mem_gb" -lt 8 ] || [ "$cores" -lt 4 ]; then rec=3
  else rec=2; fi
  echo
  say "Which speech model? Bigger models make fewer mistakes but need more time."
  say "  1) large-v3        best accuracy, needs a GPU to be fast (3 GB)"
  say "  2) large-v3-turbo  almost as good, much faster; best for a CPU (1.6 GB)"
  say "  3) medium.en       English only, for slower computers (1.5 GB)"
  say "  4) small.en        English only, fast, more mistakes (0.5 GB)"
  say "For this computer ($cores CPU cores, ${mem_gb} GB memory, ${GPU/cuda/NVIDIA GPU}) we suggest: ${B}$rec${N}"
  [ "$current" != auto ] && info "Now set to: $current"
  local pick
  while true; do
    read -r -p "  Type a number (or press Enter for $rec): " pick < $TTY
    pick=${pick:-$rec}
    [[ "$pick" =~ ^[1-4]$ ]] && break
    say "Please type a number from 1 to 4."
  done
  local model; model=$(echo "large-v3 large-v3-turbo medium.en small.en" | cut -d' ' -f"$pick")
  py -c "from earshot import config; config.save({'model': '$model'}); config.token()" \
    || die "Could not save the settings." "See the log."
  ok "Speech model: $model"
  ok "Access token made (it protects your transcripts; you see it at the end)"
}

# ---- 7. models --------------------------------------------------------------------------------
download_models() {
  step "Download the speech models"
  say "The models are downloaded once (1-3 GB) and then work without the internet."
  say "No account is needed. This can take a few minutes."
  echo
  py -m earshot.models 2>&1 | tee -a "$LOG" | grep -v "^\s*$" | sed 's/^/  /'
  [ "${PIPESTATUS[0]}" -eq 0 ] || die "A model did not download." "Check the internet connection and run the installer again."
  ok "Models ready"
}

# ---- 8. service -------------------------------------------------------------------------------
install_service() {
  step "Start Earshot"
  cat > /etc/systemd/system/earshot.service <<EOF
[Unit]
Description=Earshot transcription server
After=network-online.target
Wants=network-online.target

[Service]
User=$SVC_USER
EnvironmentFile=$ENV_FILE
Environment=PYTHONPATH=$APP_DIR/server
ExecStart=$APP_DIR/venv/bin/python -m earshot.main
Restart=always
RestartSec=5
Nice=5
UMask=0077
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF
  install -m 755 "$APP_DIR/server/earshot-cli.sh" /usr/local/bin/earshot
  systemctl daemon-reload
  systemctl enable earshot >> "$LOG" 2>&1
  systemctl restart earshot
  local i
  for i in $(seq 1 60); do
    curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" > /dev/null 2>&1 && break
    sleep 1
  done
  curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" > /dev/null 2>&1 \
    || die "Earshot did not start." "Look at the messages with: sudo journalctl -u earshot -n 50"
  ok "Earshot is running. It starts by itself when the computer starts."
  ok "Web portal on your home network: ${B}http://$(lan_ip):$PORT${N}"

  if command -v ufw > /dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
    say "The firewall (ufw) is on. Other devices at home cannot reach Earshot yet."
    if ask "Allow devices on your home network to reach Earshot (port $PORT)?" y; then
      ufw allow from 192.168.0.0/16 to any port "$PORT" proto tcp >> "$LOG" 2>&1
      ufw allow from 10.0.0.0/8 to any port "$PORT" proto tcp >> "$LOG" 2>&1
      ufw allow from 172.16.0.0/12 to any port "$PORT" proto tcp >> "$LOG" 2>&1
      ok "Firewall opened for the home network only"
    fi
  fi
}

# ---- 9. access from anywhere ------------------------------------------------------------------
set_public_url() { py -c "from earshot import config; config.save({'public_url': '$1'})"; }

wait_for_url() {   # wait_for_url https://host -> 0 when /health answers
  local url=$1 i
  say "Testing $url …"
  for i in $(seq 1 40); do
    if curl -fsS --max-time 5 "$url/health" 2>/dev/null | grep -q earshot; then
      ok "Earshot answers at ${B}$url${N}"
      return 0
    fi
    sleep 3
  done
  return 1
}

remote_cloudflare() {
  echo
  say "${B}Cloudflare Tunnel${N}"
  say "Your server makes an outgoing connection to Cloudflare. Cloudflare gives it an https address"
  say "on your domain. No router changes, and your home IP address stays hidden. It is free."
  say "You need: a free Cloudflare account and a domain name (about \$10 a year if you do not have one)."
  echo
  if ! command -v cloudflared > /dev/null; then
    mkdir -p --mode=0755 /usr/share/keyrings
    run "Installing cloudflared" sh -c 'curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg && echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" > /etc/apt/sources.list.d/cloudflared.list && apt-get update -q && apt-get install -y -q cloudflared' \
      || die "Could not install cloudflared." "See https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
  fi
  ok "cloudflared $(cloudflared --version 2>/dev/null | awk '{print $3}') is installed"

  say "${B}A. Account and domain${N}"
  say "  1. Open https://dash.cloudflare.com/sign-up and make a free account (skip if you have one)."
  say "  2. Add your domain: on the home page click 'Add a domain' (or 'Onboard a domain')."
  say "     No domain yet? Use 'Domain Registration' > 'Register Domains' in the menu to buy one."
  say "  3. If you added a domain you already own, Cloudflare shows two 'nameservers'. Put them in at"
  say "     the company where you bought the domain. The domain shows 'Active' when it is done"
  say "     (often 10 minutes, sometimes a few hours). A domain bought at Cloudflare is active at once."
  pause

  say "${B}B. Make the tunnel${N}"
  say "  1. Open https://one.dash.cloudflare.com  (Cloudflare Zero Trust; it may ask you to pick a"
  say "     team name and the Free plan the first time. That is fine.)"
  say "  2. In the menu: Networks > Tunnels. Click 'Create a tunnel'."
  say "  3. Pick 'Cloudflared'. Name it: earshot. Click 'Save tunnel'."
  say "  4. The next page shows a command to install the connector. Copy any of the commands"
  say "     shown (they all hold the same long token that starts with 'eyJ')."
  echo
  local line token=""
  while [ -z "$token" ]; do
    read -r -p "  Paste the command (or just the token) here and press Enter: " line < $TTY
    token=$(echo "$line" | grep -oE 'eyJ[A-Za-z0-9_=-]+(\.[A-Za-z0-9_=-]+)*' | head -1)
    [ -n "$token" ] || say "I could not find the token (it starts with eyJ). Please copy it again."
  done
  cloudflared service uninstall >> "$LOG" 2>&1 || true
  run "Connecting this computer to Cloudflare" cloudflared service install "$token" \
    || die "cloudflared could not start with this token." "Copy the command again from the tunnel page and run 'sudo earshot remote'."
  sleep 3
  systemctl is-active --quiet cloudflared && ok "Tunnel connector is running" || warn "The connector is not running yet; it may need a moment."
  say "  On the Cloudflare page the connector should now show as 'Connected'. Click 'Next'."
  echo
  say "${B}C. Give it an address${N}"
  say "  On the 'Route traffic' or 'Public Hostnames' page:"
  say "  1. Subdomain: earshot      Domain: pick your domain"
  say "  2. Service type: HTTP      URL: localhost:$PORT"
  say "  3. Click 'Save' (or 'Complete setup')."
  echo
  local host url
  while true; do
    read -r -p "  Type the full address you made (for example earshot.example.com): " host < $TTY
    host=${host#https://}; host=${host#http://}; host=${host%%/*}
    [ -n "$host" ] || continue
    url="https://$host"
    if wait_for_url "$url"; then
      set_public_url "$url"
      return 0
    fi
    bad "$url does not answer yet."
    say "Check: the tunnel shows 'Healthy', the hostname points to HTTP localhost:$PORT, and the"
    say "domain is 'Active'. A new address can take a few minutes."
    ask "Try again?" y || { warn "Skipped. Run 'sudo earshot remote' later."; return 1; }
  done
}

remote_tailscale() {
  echo
  say "${B}Tailscale Funnel${N}"
  say "Tailscale gives this server a free https address like https://earshot.tail1234.ts.net."
  say "No domain and no router changes needed. The phone does not need Tailscale."
  say "You need: a free Tailscale account (sign in with Google, Microsoft, GitHub or Apple)."
  echo
  if ! command -v tailscale > /dev/null; then
    run "Installing Tailscale" sh -c 'curl -fsSL https://tailscale.com/install.sh | sh' \
      || die "Could not install Tailscale." "See https://tailscale.com/download/linux"
  fi
  ok "Tailscale is installed"
  if ! tailscale status > /dev/null 2>&1; then
    say "Now sign in. A link shows below: open it on any computer or phone, sign in, and"
    say "click 'Connect'. This window continues by itself after that."
    echo
    tailscale up --hostname=earshot < $TTY || die "Tailscale sign-in did not finish." "Run 'sudo earshot remote' to try again."
  fi
  ok "Signed in to Tailscale"
  echo
  say "Now turn on Funnel (it makes the address reachable from the internet)."
  say "If a link shows, open it, click 'Enable' (for HTTPS and for Funnel), then come back."
  echo
  tailscale funnel --bg "$PORT" < $TTY 2>&1 | tee -a "$LOG" | sed 's/^/  /'
  local dns
  dns=$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null)
  [ -n "$dns" ] || die "Could not find the Tailscale address." "Run 'tailscale status' to check, then 'sudo earshot remote'."
  local url="https://$dns"
  if wait_for_url "$url"; then
    set_public_url "$url"
    return 0
  fi
  bad "$url does not answer yet."
  say "Check https://login.tailscale.com/admin/dns: 'HTTPS Certificates' must be on. Then run 'sudo earshot remote'."
  return 1
}

setup_remote() {
  step "Use Earshot away from home (optional)"
  local cur; cur=$(public_url)
  say "At home, the phone reaches the server over Wi-Fi. Away from home it needs an https address"
  say "on the internet. Both choices below are free, need no VPN app on the phone, and need no"
  say "changes to your router. Only devices with your access token can use Earshot."
  [ -n "$cur" ] && info "Now set to: $cur"
  echo
  say "  1) Tailscale Funnel   - recommended. No domain needed, and the encryption ends on THIS computer,"
  say "                          so nobody in between can read your recordings. Address: https://earshot.<name>.ts.net"
  say "  2) Cloudflare Tunnel  - if you have a domain. Note: Cloudflare decrypts the traffic on its servers"
  say "                          before passing it on, so Cloudflare could technically see recordings and transcripts."
  say "  3) Not now            - home Wi-Fi only (you can do this later: sudo earshot remote)"
  echo
  case $(choose "Type 1, 2 or 3:" 3) in
    1) remote_tailscale ;;
    2) remote_cloudflare ;;
    3) [ -z "$cur" ] && set_public_url "http://$(lan_ip):$PORT"
       warn "Home Wi-Fi only. Tip: give this computer a fixed IP address in your router, so the address stays the same." ;;
  esac
}

# ---- 10. connect the phone and AI -------------------------------------------------------------
connect_devices() {
  step "Connect your phone and your AI tools"
  say "${B}Phone${N}"
  py -m earshot.pair phone
  pause
  say "${B}AI tools (Claude and others)${N}"
  py -m earshot.pair ai
}

finish() {
  local url; url=$(public_url)
  echo
  echo "${D}────────────────────────────────────────────────────────────${N}"
  echo "${G}${B}  Earshot is ready.${N}"
  echo
  say "Web portal:  ${B}${url:-http://$(lan_ip):$PORT}${N}   (at home also http://$(lan_ip):$PORT)"
  say "Sign in with the access token. Show it again with: ${B}sudo earshot token${N}"
  echo
  say "Useful commands:"
  say "  sudo earshot status    is it running, what is it doing"
  say "  sudo earshot pair      show the phone QR code and the AI address again"
  say "  sudo earshot remote    set up access from outside your home"
  say "  sudo earshot update    get the newest version"
  say "  sudo earshot logs      show what the server is doing"
  echo
}

uninstall() {
  need_root "$@"
  echo "This removes the Earshot service and programs."
  ask "Continue?" n || exit 0
  systemctl disable --now earshot 2>/dev/null
  rm -f /etc/systemd/system/earshot.service /usr/local/bin/earshot
  systemctl daemon-reload
  rm -rf "$APP_DIR" /opt/earshot-python
  ok "Programs removed."
  if ask "Also delete all transcripts and the access token in $DATA_DIR? This cannot be undone." n; then
    rm -rf "$DATA_DIR" "$CONF_DIR"; userdel $SVC_USER 2>/dev/null
    ok "Transcripts deleted."
  else
    say "Transcripts kept in $DATA_DIR."
  fi
  command -v cloudflared > /dev/null && info "cloudflared is still installed. Remove: sudo cloudflared service uninstall && sudo apt remove cloudflared"
  command -v tailscale > /dev/null && info "Tailscale is still installed. Stop Funnel: sudo tailscale funnel reset"
}

# ---- main -------------------------------------------------------------------------------------
case "${1:-}" in
  --remote)
    need_root "$@"; TOTAL_STEPS=2; setup_remote; connect_devices; exit 0 ;;
  --update)
    need_root "$@"; TOTAL_STEPS=4
    GPU=$(grep -q LD_LIBRARY_PATH "$ENV_FILE" 2>/dev/null && echo cuda || echo cpu)
    get_code; install_python; download_models
    step "Restart"; systemctl restart earshot; sleep 3
    systemctl is-active --quiet earshot && ok "Earshot is running the new version" || die "Earshot did not start." "sudo journalctl -u earshot -n 50"
    exit 0 ;;
  --uninstall)
    uninstall "$@"; exit 0 ;;
esac

need_root "$@"
clear 2>/dev/null || true
cat <<EOF

  ${B}earshot${N}  ·  server installer

  This sets up your own transcription server. Your phone sends recordings here, this computer
  turns them into text with speaker labels, and AI tools like Claude can read the transcripts.
  Nothing goes to a transcription company.

  What happens (about 10-20 minutes):
    1. Check this computer          6. Choose settings
    2. Install needed programs      7. Download the speech models
    3. Look for a graphics card     8. Start Earshot
    4. Download Earshot             9. Optional: use it away from home
    5. Install speech software     10. Connect your phone and AI tools

  The installer explains each step and checks that it worked. You can stop at any time
  (Ctrl+C) and run it again later; it continues where it stopped.
EOF
pause

check_system
install_packages
detect_gpu
get_code
install_python
configure
download_models
install_service
setup_remote
connect_devices
finish
