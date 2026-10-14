# ServerSync 1.1.2

Minecraft 1.21.1 / NeoForge 21.1.219+ (21.1.x) / Java 21+.

ServerSync automatically checks selected server mods before joining, downloads changed jars through the Minecraft server's existing TCP port, and applies them after the client exits. A restart is required before joining with the updated mods.

- Server config lists monitored mod IDs from the server's own `mods` directory.
- No separate download port or client synchronization URL configuration.
- SHA-256 verification, backups, and rollback on ordinary file-operation failures.
- Version 1.1.2 fixes Mixin package isolation and commented TOML mod table detection.

Install the same ServerSync jar on the server and every client. Do not keep an older ServerSync jar alongside it. See README.md for configuration and recovery instructions.

Validation: 14 automated tests passed. A Linux server running NeoForge 21.1.233 successfully started; same-port mod download hashes and a Minecraft status handshake were verified. Full player login, client UI, SRV resolution, and Windows game-exit file replacement still require manual testing.

This is a synchronization tool, not anti-cheat. Joining a server can install executable mod code supplied by that server. Connect only to trusted servers. The shared-port download endpoint uses plain HTTP. Minecraft-aware proxies need compatible protocol routing.
