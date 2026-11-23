"""Prints what the phone and AI tools need: a QR code for the phone, the token, the MCP address.

  python -m earshot.pair [phone|ai|all]
"""

import socket
import sys

from . import config


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # no packet is sent; this just picks the outgoing interface
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def address() -> str:
    return config.load()["public_url"].rstrip("/") or f"http://{lan_ip()}:{config.PORT}"


def phone() -> None:
    import qrcode
    url, token = address(), config.token()
    q = qrcode.QRCode(border=2)
    q.add_data(f"{url}/pair#{token}")
    q.make(fit=True)
    print()
    q.print_ascii(invert=True)
    print(f"""
  1. Install the Earshot app on the phone.
  2. Open the phone camera and point it at the code above.
  3. Tap the link, then tap "Connect Earshot". The app says "Connected".

  No camera? In the app: Settings > Server, then type:
     Address: {url}
     Token:   {token}
""")
    if not url.startswith("https://"):
        print("  Note: this address works only on your home Wi-Fi. For anywhere: sudo earshot remote\n")


def ai() -> None:
    url, token = address(), config.token()
    print(f"""
  MCP address (keep it private, it holds your token):
     {url}/mcp/{token}

  Claude (web, desktop, phone app):
     1. Open https://claude.ai/settings/connectors
     2. Click "Add custom connector".
     3. Name: Earshot.  URL: the MCP address above.  Click "Add".

  Claude Code:
     claude mcp add --transport http earshot {url}/mcp/{token}

  Other MCP apps: use {url}/mcp with the header "Authorization: Bearer {token}".
""")
    if not url.startswith("https://"):
        print("  Note: Claude on the web needs an https address. Set one up with: sudo earshot remote\n")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("phone", "all"):
        phone()
    if what in ("ai", "all"):
        ai()
    if what == "token":
        print(config.token())
    if what == "url":
        print(address())
