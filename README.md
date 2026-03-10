# JewAuth

A client-side Fabric mod for Minecraft 1.21.11.

---

## Features

- **Token Login** — Authenticate using a JWT session token directly from the Multiplayer screen
- **Session Restore** — Revert to your original session at any time
- **Token Browser** — Browse, search, sort, and manage all accounts with per-account notes
- **Account Manager** — Auto-parse accounts from Prism, MultiMC, Modrinth, ATLauncher, GDLauncher, and plain text files
- **Token Refresh** — Refresh expired tokens via Microsoft authentication chain
- **Proxy Support** — SOCKS4, SOCKS5, and HTTP CONNECT with auto-detection and authentication
- **Proxy Manager** — Save, edit, and switch between multiple proxy configurations
- **Proxy Rotation** — Automatic rotation every 3 refreshes with auto-failover on dead proxies
- **Hypixel Skyblock Stats** — Per-account SB level, coop status, and profile tracking via Hypixel API
- **Anti-Kick** — Auto-reconnect when kicked for "logged in from another location"
- **Selfban** — One-click server self-ban with confirmation
- **Name Changer** — Change your IGN via the Mojang API
- **Nick Hider** — Client-side name replacement across chat, tab list, and scoreboard
- **Persistent Cache** — Refreshed tokens, notes, and Skyblock data survive restarts

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Place the mod `.jar` in your `.minecraft/mods/` directory

## Configuration

All config is stored in `.minecraft/config/tokenlogin/`. Source launcher files are never modified.

## License

[MIT](LICENSE)

---

# ⚠️ USE AT YOUR OWN RISK

This mod interacts with Minecraft's authentication and session management in ways that may violate Mojang's Terms of Service. The authors assume no responsibility for banned accounts, lost access, or any other consequences resulting from the use of this software. By using this mod, you accept full responsibility.
