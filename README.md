# JewAuth

Fabric mod for Minecraft 1.21.11. Login with session tokens, manage proxies, swap accounts on the fly.

**Use at your own risk.** This mod interacts with Minecraft's authentication in ways that may violate Mojang's ToS. You are solely responsible for any consequences — banned accounts, lost access, whatever. Don't come crying.

---

## Features

**Token Login**
- Paste a JWT session token and inject it as your active session
- Token expiry validation with live countdown
- Restore your original session at any time
- Token browser — browse and manage saved tokens

**Account Manager**
- Auto-parse accounts from launcher files (Prism, MultiMC, GDLauncher, plain .txt)
- Refresh expired tokens via Microsoft auth chain
- Dead list — dismiss accounts you don't want to see
- Per-account notes and token caching

**Proxy Support**
- SOCKS4 / SOCKS5 / HTTP CONNECT — auto-detects working protocol
- Route game connections through your proxy
- Proxy browser with saved proxy list
- Auth support (username/password)

**Name Changer**
- IGN mode — change your in-game name via Mojang API
- Hider mode — client-side nick replacement across all rendering (chat, tab list, scoreboard)

**Selfban**
- One-click toggle to get yourself banned from a server (confirmation required)

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop the mod jar into `.minecraft/mods/`
4. Launch the game — everything lives on the Multiplayer screen

## Config

Stored in `.minecraft/config/tokenlogin/`. Proxy settings and cached tokens persist between sessions. Source launcher files are never modified.

---

## License

MIT

---

**Again: your accounts, your risk. No warranty. No support guarantees.**
