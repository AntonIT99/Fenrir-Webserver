# Fenrir Webserver

Personal website hosted on a Raspberry Pi, built with Java 17, Spring Boot, Thymeleaf, and SQLite. It includes approved-user login, Minecraft server status, a public Minecraft mod catalogue with admin editing, and the Skadi interface.

## Development

Run commands from the repository root:

```powershell
.\gradlew.bat bootRun
.\gradlew.bat test bootJar
py -m unittest discover -s scripts/tests -v
```

Local startup uses `data/webserver.sqlite` and creates a development admin if needed. See the [deployment guide](docs/deployment/raspberry-pi.md) for configuration and production credentials. Tests use isolated databases.

## Repository layout

| Path | Contents |
| --- | --- |
| `src/main/java` | Application, authentication, Minecraft, catalogue, and Skadi services |
| `src/main/resources` | Configuration, Thymeleaf templates, static assets, and catalogue migrations |
| `src/test/java` | Application and integration tests |
| `scripts/` | Compact research client, generic API client, and SQLite backup utility |
| `scripts/tests/` | Research-client tests and isolated packaged-application smoke test |
| `catalogue/imports/` | Dated, sourced catalogue research snapshots |
| `catalogue/examples/` | Synthetic API example shared by tests and documentation |
| `docs/deployment/` | Raspberry Pi deployment instructions |
| `docs/mod-catalogue/` | Catalogue operations/API guide and implementation plan |
| `.agents/skills/` | Project-specific research skill, routed by `AGENTS.md` |

## Minecraft catalogue

- [Live catalogue](https://fenrir-netz.duckdns.org/games/minecraft/mods): public browsing; editing requires an admin login.
- [Operations and API](docs/mod-catalogue/operations.md): private research access, client commands, backups, and verification.
- [Implementation plan](docs/mod-catalogue/plan.md): delivered stages and remaining work.
- [Research skill](.agents/skills/research-minecraft-mods/SKILL.md): sourced updates for user-requested mods.

The Raspberry Pi is the authoritative catalogue. On the configured workstation, `py scripts/mod_research.py status` uses the saved encrypted credential and automatic SSH tunnel. Full research records stay in files; the client returns compact receipts. The repository imports are dated snapshots, not a replacement for fresh checkout before editing a live record.

## Deployment and local files

Use the existing root entry point, `.\deploy-to-pi.ps1`, after preparing the ignored `deploy-to-pi.local.ps1` from its example. Follow the [deployment guide](docs/deployment/raspberry-pi.md) and [catalogue backup procedure](docs/mod-catalogue/operations.md#pi-configuration-and-backup-procedure) before production changes.

Generated builds, temporary research, and preview logs belong under ignored `build/`. Databases, environment files, deployment credentials, SSH keys, and the encrypted workstation connection must stay outside version control.
