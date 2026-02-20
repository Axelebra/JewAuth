# JewAuth

Fabric mod for Minecraft 1.21.11 — token-based session login, proxy routing, name management, and more.

## ⚠️ Disclaimer

This project is provided **for educational and research purposes only**. The authors do not encourage, endorse, or promote the use of this software to violate any game's Terms of Service, EULA, or rules. **Use at your own risk.**

By using this software you acknowledge that:
- You are solely responsible for how you use it
- Using this on third-party servers may result in permanent bans
- The authors are not liable for any consequences, including account bans, suspensions, or other actions taken against you
- This project is not affiliated with or endorsed by Mojang, Microsoft, or any server network

**If you don't fully understand what this does, don't use it.**

## Features

- **Token Login** — authenticate with a session token instead of Microsoft auth, with JWT validation and expiry tracking
- **Proxy Support** — route connections through SOCKS4, SOCKS5, or HTTP proxies with auto-detection
- **IGN Changer** — change your username via the Mojang API
- **Nick Hider** — client-side name replacement across all rendered text (chat, tab list, scoreboard, nameplates)

## Building

```
./gradlew build
```

Output: `build/libs/JewAuth-1.0.jar`

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) ≥ 0.18.0 for Minecraft 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `JewAuth-1.0.jar` into your `.minecraft/mods` folder

## Requirements

- Minecraft 1.21.11
- Fabric Loader ≥ 0.18.0
- Fabric API
- Java 21+

## License

MIT — see [LICENSE](LICENSE) for details.
