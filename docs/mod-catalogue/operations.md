# Mod catalogue: first implementation slice

Status (22 October 2026): deployed on the Raspberry Pi with all 28 requested mods, grouped into 23 families / 42 projects including relevant dependency and upstream counterparts. The production catalogue contains 8 scoped, sourced incompatibility relationships. The existing user was preserved. Exact mod-release versions are intentionally not persisted in these records. Synthetic test fixtures were not imported to the Pi.

Live catalogue: `https://fenrir-netz.duckdns.org/games/minecraft/mods` (also available through `http://pi/games/minecraft/mods`). Future requested research updates go directly to the Pi through the compact client; no local fallback or recurring schedule is configured.

The workstation connection is saved outside Git under `%LOCALAPPDATA%/Wolfsnetz/mod-catalogue/connection.json`. Its token is encrypted with Windows DPAPI for the current user. The client opens a temporary localhost port 19081 SSH tunnel to `pi@pi`, Pi loopback port 8081, and closes tunnels it created when it exits. Run `py scripts/mod_research.py status` without overrides to check the authoritative Pi connection. Environment tokens can override credentials; an explicit base override is only for intentional alternate/test environments. On a new workstation, provision a token in the process and use `configure --ssh pi@pi --local-port 19081`; installing the skill alone does not transfer credentials.

Deployment retained the original pre-catalogue JAR/database in `/opt/heimserver/backups/before-catalogue-20261022T214723Z`, a second pre-fix snapshot in `/opt/heimserver/backups/before-catalogue-20261022T215203Z`, and a verified post-import database backup at `/opt/heimserver/backups/catalogue-initial-20261022T215351Z.sqlite`. A systemd drop-in loads the separate research configuration without changing the existing website environment. Caddy continues to forward only to website port 8080.

## Website browsing and administration

The existing Minecraft parent page at `/games/minecraft` lists **Minecraft Mods** first and **Minecraft Server** second. The `/minecraft` alias now points to that parent. Browsing and searching `/games/minecraft/mods` and its family detail pages requires no login. Only published, unarchived families appear; drafts and archives return 404 on public detail routes. Search supports names, aliases, themes, exact versions, loader, availability, maintenance, and released builds on both primary targets. Public related-project links resolve only to other published, unarchived families.

Admins can open `/admin/mods` from the existing dashboard. Route-level and controller-level ADMIN checks protect the list, editor, preview, import/export, and history. Browser writes retain CSRF protection and audit the actual logged-in username. The browser editor uses the shared catalogue service; it does not expose the private API token.

- `/admin/mods/new`: create a family with guided fields for projects, platform listings, support/releases, relationships, evidence, and branch comparisons. New records default to drafts.
- `/admin/mods/{id}/edit`: edit a complete family, validate without saving, supply a change reason, and save against the loaded revision. Stale edits return a conflict while preserving the unsaved form. Saved family/project IDs are fixed; existing projects cannot be removed.
- `/admin/mods/{id}/preview`: inspect the saved detail page, including unpublished records.
- `/admin/mods/{id}/history`: inspect snapshots and restore one as a new audited revision, with the current expected revision and a reason.
- `/admin/mods/import`: paste/load a batch, preview its changes, and commit only after preview succeeds. Editing the batch invalidates the preview. Export downloads every current family and revision, including drafts/archives; this export is not a full database backup.

The browser editor needs JavaScript; public browsing, filters, detail pages, and history restoration use server-rendered HTML. Version tables scroll horizontally on small displays, and editor fields stack at narrow widths. Full phone-width browser verification remains pending because browser tools became unavailable during the test session.

Local browser checks confirmed anonymous browsing and details, alias/loader filtering, the dual-target empty state, admin login, guided validation/save, history restoration, import preview/commit and preview invalidation, JSON download, and a two-tab stale-edit conflict with unsaved values retained. Checks used an isolated database under `build/catalogue-preview-*`, never the existing local or Pi database. Manual new-family entry was started but its completion was interrupted when browser tools became unavailable; automated tests confirm the new-family page and shared create endpoint. A narrow-screen check and screenshot capture are still pending. These browser checks preceded the production deployment described above.

## Inspected Raspberry Pi setup (22 October 2026)

Read-only SSH to `pi@pi` succeeded. The Pi is `raspberrypi`, with LAN IPv4 `192.168.178.29/24` and both local and global IPv6 addresses. `webserver.service` runs `/opt/heimserver/app.jar` in `/opt/heimserver`, with the existing database at `/opt/heimserver/data/webserver.sqlite`. Its application listener is loopback port 8080.

Caddy's `/etc/caddy/Caddyfile` forwards both `http://pi` and `fenrir-netz.duckdns.org` to `127.0.0.1:8080`. IPv4 and IPv6 filter policies were ACCEPT, with no filtering rules shown. Neither `cloudflared` nor `tailscaled` was active. Router forwarding, other services' exposure, and off-LAN routing have not been verified. The systemd service currently has no explicit service user; changing that requires checking ownership of all application resources separately.

The original proposed shared-listener gateway-secret approach is replaced for this first slice by a distinct loopback connector:

```text
Public browser -> Caddy -> 127.0.0.1:8080 -> website
                                          /internal/** always rejected

Local Codex -> localhost:19081 -> encrypted SSH -> Pi 127.0.0.1:8081
                                                   dedicated bearer token
                                                   catalogue endpoints only
```

The API is reachable from the local workstation through SSH without opening a LAN port. Direct LAN HTTPS access remains a later deployment task: configure a private gateway, restrict both address families, and verify external denial before enabling it. Do not point the public Caddy reverse proxy at port 8081.

## Local startup

### Compact client for repeated research

Use `py scripts/mod_research.py` for agent-driven research instead of the verbose generic PowerShell client. It uses Python's standard library, existing authenticated endpoints, and no LLM calls. Full records stay in working files; console output is compact JSON. This reduces conversation tokens rather than claiming to eliminate the reasoning needed to research accurate facts.

```powershell
py scripts/mod_research.py status
py scripts/mod_research.py find "requested mod"
# Choose checkout for an existing family, or new for a new family.
py scripts/mod_research.py checkout family-id --out build/mod-research/job.json
py scripts/mod_research.py new new-family --name "New family" --out build/mod-research/new.json
py scripts/mod_research.py show build/mod-research/job.json --pointer /projects/0/support
py scripts/mod_research.py template evidence
py scripts/mod_research.py patch build/mod-research/job.json --changes build/mod-research/patch.json
py scripts/mod_research.py upload build/mod-research/job.json --reason "Researched requested mod support and dependencies"
```

The upload command alone handles current-revision checks, unchanged-entry skipping, server dry-run validation, transactional upload, and readback verification. Its receipt contains only IDs/revisions, change status, and errors. An unchanged retry writes nothing. Stale edits fail before upload; server revision checks still protect the gap between preflight and commit. If a response is lost, rerun the same file: equal stored data is skipped. A successful commit with failed readback is reported explicitly instead of claiming verified success.

`new` scaffolds an unpublished family and a project with all four initial targets set UNKNOWN; fill descriptions and researched data before uploading. `patch` reads a JSON object mapping family-relative pointers to replacements; `/evidence/-` appends one source. It preserves every unmentioned field and prints changed field paths rather than content. Use `show --pointer` and `template TYPE` to avoid rereading full entries or schema documentation. `checkout`/`new` refuse to overwrite an existing job file. The client can also upload `{entries:[…]}` files with up to 20 family entries, preserving transaction boundaries.

Connection configuration: normally use the saved encrypted Pi connection described above. For intentional isolated testing, set `MODS_API_TOKEN` plus `MODS_API_BASE` or `--base` to a localhost origin. Without a saved connection the fallback origin is `http://127.0.0.1:18081`; the project skill prohibits silently falling back when Pi access fails. Remote origins, embedded URL credentials, redirects, and inherited HTTP proxies are rejected/disabled. Tokens are never written into research files or passed in CLI arguments.

Project-specific instructions are in `.agents/skills/research-minecraft-mods/SKILL.md`, routed by `AGENTS.md`. The skill handles user-requested research with source evidence and this client; it does not schedule work. Load its short record reference only when needed.

Client verification: 11 unit tests pass, and `scripts/tests/smoke_mod_research.py` exercised new/patch/upload/readback/no-op retry/search/checkout/update/conflict against a disposable Java 17 application and SQLite database. In that synthetic example the upload receipt was 91 characters versus 1,938 for the pretty-printed full entry (95% fewer output characters; this is not a token or real-research benchmark). Run tests with `py -m unittest discover -s scripts/tests -v`.

Use a separate terminal in the repository. Generate a 256-bit token and keep it in that terminal's environment; only its SHA-256 hash is passed to the application configuration:

```powershell
$taskTokenBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($taskTokenBytes)
$env:MODS_API_TOKEN = [Convert]::ToHexString($taskTokenBytes).ToLowerInvariant()
$env:MODS_API_TOKEN_SHA256 = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes($env:MODS_API_TOKEN)
    )
).ToLowerInvariant()
$env:MODS_API_ENABLED = 'true'
$env:MODS_API_PORT = '18081'
# Use a disposable development database for the demonstration.
$env:SPRING_DATASOURCE_URL = 'jdbc:sqlite:./build/catalogue-demo.sqlite'
.\gradlew.bat bootRun
```

These examples require PowerShell 7 with modern .NET. Run the client in a process that has the same `MODS_API_TOKEN`; the raw token is a secret and must not be committed, printed, or placed in literal command arguments. Stop the server and unset the development variables when finished. Without `MODS_API_ENABLED=true`, there is no additional listener and internal API requests return 404.

For Pi access after activation, keep this tunnel open in a separate terminal:

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18081:127.0.0.1:8081 pi@pi
```

The client deliberately accepts localhost origins only and does not follow redirects. It uses `MODS_API_TOKEN` from the calling process:

```powershell
.\scripts\mod-catalogue.ps1 -Path /targets
.\scripts\mod-catalogue.ps1 -Method POST -Path /batches -BodyFile .\catalogue\examples\demo-batch.json -DryRun
.\scripts\mod-catalogue.ps1 -Method POST -Path /batches -BodyFile .\catalogue\examples\demo-batch.json
.\scripts\mod-catalogue.ps1 -Path '/families?q=Reforged&tag=scifi&minecraftVersion=1.21.1&loader=NEOFORGE'
.\scripts\mod-catalogue.ps1 -Path /families/catalogue-demo
.\scripts\mod-catalogue.ps1 -Path /families/catalogue-demo/history
```

## API contract: `/internal/mod-catalogue/v1`

All endpoints require `Authorization: Bearer <token>` and must arrive on the configured internal connector from loopback. Browser sessions do not authorize API access. Client-supplied Host and forwarding headers do not identify private ingress. Enabling forwarded-header processing while the API is enabled fails startup. The configured token hash is validated at startup, and the port must be distinct from the website port and in 1024–65535.

| Method/path | Behavior |
| --- | --- |
| `GET /targets` | Configured exact version/loader pairs |
| `GET /families` | Paginated search; `q`, `tag`, `minecraftVersion`, `loader`, `availability`, `includeArchived`, `offset`, `limit` |
| `GET /families/{id}` | Complete family aggregate, revision, update time |
| `GET /families/{id}/history` | Newest revisions first; `offset`, `limit` |
| `GET /export` | Same paginated format as search; use `includeArchived=true` for all states |
| `POST /batches?dryRun=true` | Full validation and write simulation, then rollback |
| `POST /batches` | Transactional create/update of one or more complete families |

Pagination defaults to 25 with a maximum of 100; repeat export pages until all records are retrieved. This first API exports entries, not credentials, audit tables, or the entire SQLite file. An export is not a full backup. To reimport exported content, construct a new batch with a new requestId, reason, and the destination's expected revisions.

Batch format: `requestId`, `reason`, and `changes` containing `expectedRevision` and `family`. See `catalogue/examples/demo-batch.json` for the complete field structure. All list fields are required, using `[]` when empty. Unknown JSON fields and enum values are rejected. Dates use `YYYY-MM-DD`; release publication times use ISO instants. URLs require HTTPS and must not contain credentials. Evidence URLs are stored, not fetched.

Rules:

- A create uses expected revision 0. Updates send the latest revision and the complete desired family. Omitted list members are removed except projects: project IDs are permanent and cannot be removed or moved between families in this slice.
- IDs use lowercase letters, digits, underscores, and hyphens, maximum 100 characters. Use globally unique project IDs and family-local evidence/comparison IDs.
- The same requestId and same batch content returns its original successful response without another write. Reusing that ID with different content returns 409. Generate a new ID for each intended change. Reason and expected revision are part of the content.
- Dry runs acquire the normal transaction lock and exercise the real constraints, but leave no family, revision, request, target, or projection changes. The same requestId can subsequently commit. Previewing an already committed requestId returns 409.
- Maximum request body is 1 MiB, including chunked requests. Maximum 20 families per batch; collection and text limits are enforced in `ModValidation`.
- Family updates, project indexes, scoped relationships, evidence, comparisons, history, and retry records commit or roll back together. References may point to projects already stored or created anywhere in the same batch.
- Exact target pairs are registered when a valid support assessment introduces them. Initial pairs are Forge 1.7.10, 1.12.2, 1.20.1, and NeoForge 1.21.1. Wildcards such as `26.x` are not release support targets.
- Availability values: `UNKNOWN`, `RELEASED`, `ANNOUNCED`, `NO_BUILD_FOUND`. Maintenance: `UNKNOWN`, `ACTIVE`, `FIXES_ONLY`, `DISCONTINUED`. Parity: `UNKNOWN`, `EQUIVALENT`, `PARTIAL`, `DIVERGENT`.
- Claimed support/maintenance needs a check date and evidence IDs. `RELEASED` requires verified evidence of a downloadable build, but exact release metadata is optional: use `releases:[]` to follow the user's preference. A non-unknown parity claim needs evidence and a date. Relationships always need evidence, and an optional scope must match a source-project support target. Confirmed incompatibilities additionally need nonblank notes and at least one non-uncertain source; warnings appear from both affected family pages.
- Comparisons explicitly name both project/version/loader branches in the family. The schema does not infer compatibility from matching targets.
- Listings are unique by platform/project and platform/external ID. An individual project may have a CurseForge and a Modrinth listing. Platform metadata refresh/import has not yet been implemented; future fetched metadata must have its own provenance and must not replace curated fields automatically.
- Archive/restore is an ordinary audited update of `archived`. Restoring a historical snapshot means submitting that snapshot with the *current* expected revision and a new reason/requestId; history is never rewritten. `published` controls visibility in the public catalogue; drafts and archives remain private.

Responses: 200 for successful reads and batches; 400 for invalid payloads/query parameters/references; 401 for an invalid token on the private listener; 404 for disabled API, wrong listener/path, or a missing family; 409 for revision, retry-ID, database constraint, or concurrent-write conflicts; 413 for oversized requests. All successful writes identify the actor as `catalogue-research`; supplied payloads cannot choose their audit identity.

## Storage and migrations

Catalogue tables live alongside `users` in the existing SQLite file. Canonical family JSON supports complete API round trips and historical snapshots. Transactionally maintained relational tables index projects, aliases, tags, listings, support, relationships, evidence, and comparisons. Never edit either representation directly; use the application service.

`ModCatalogueMigrations` applies the additive SQL resource transactionally, recording a SHA-256 checksum in `mod_schema_versions`. Repeated startup is a no-op; changing an already applied script fails startup. Add future numbered scripts and register their execution in the migrator rather than editing applied SQL. This mechanism owns catalogue tables only and does not baseline or modify `users`. Foreign keys and a five-second busy timeout are enabled for every application SQLite connection.

## Pi configuration and backup procedure

Production is activated using the following configuration shape. Keep the public Caddy forwarding target at 8080. `/opt/heimserver/mod-catalogue.env` has mode 600 and contains only the API enable flag, private port, and token hash:

```text
MODS_API_ENABLED=true
MODS_API_PORT=8081
MODS_API_TOKEN_SHA256=<64 hexadecimal SHA-256 characters>
```

The deployment script now includes `EnvironmentFile=-/opt/heimserver/mod-catalogue.env` in the service it generates. The leading minus means the file is optional; the script does not create, overwrite, or print it. It continues to rewrite its separate `webserver.env`. Existing installed service definitions need regeneration or an equivalent systemd drop-in before they load the new file. Token rotation replaces the hash and restarts the service; disabling the API removes its listener on restart.

Before deployment, retain the previous JAR and service/configuration files and create a consistent database snapshot. The Pi has Python 3.11 available but no sqlite3 CLI was found. Copy `scripts/backup-sqlite.py` to a controlled location and run as the database owner:

```bash
python3 backup-sqlite.py /opt/heimserver/data/webserver.sqlite /path/to/private-backups/webserver-before-mods.sqlite
```

The destination directory must exist and must be private. The script creates a new destination exclusively with mode 600, uses SQLite's online backup API, and checks its integrity. It fails if the destination exists. Retain a separate protected copy off the Pi. Test restoration into an isolated directory and run integrity checks there before relying on the backup.

Rollback: stop the application, preserve the failed database and any `-wal`/`-shm` files together, restore the verified snapshot and previous JAR/configuration, remove any stale journal sidecars from the restored database path only while stopped, then restart and check login and existing features. Restoring the snapshot discards subsequent writes: take a fresh backup and reconcile later changes before choosing database rollback. The additive first migration normally permits reverting the JAR without restoring the database; a failed migration itself rolls back transactionally.

## Verification and remaining work

Local verification on 22 October 2026: all 24 automated tests passed (including 10 catalogue integration tests), and `test bootJar` succeeded. A separate packaged-JAR check on Java 17 exercised the supplied PowerShell client for preview, commit, read, export, and persistence after stopping/restarting the application. The online backup tool was checked against an isolated WAL-mode database, including data recovery and rejection of an existing backup destination. No real mods or live Pi data were modified.

Tests use a temporary SQLite database and real HTTP on separate website/research connectors. They cover private token access, public denial despite valid credentials and forged headers, wrong-path denial, no session creation, disabled defaults, dry-run rollback, retry deduplication, stale revision rejection, complete batch rollback, required evidence, strict payload parsing, archive/restore history, migration repeatability, user preservation, foreign-key enforcement, body limits, and pagination limits.

The latest `test bootJar` run passes all 32 tests, including availability without exact release storage, verified incompatibility evidence, reverse incompatibility lookup, and real pagination above twelve families. All 12 Python client tests pass, including DPAPI credential round-trip. The skill validator passes.

Production verification: 23 families / 42 projects / 8 incompatibility relationships, SQLite integrity OK, existing user retained, no exact release records, public pages and page two render completely, and data survives the corrective deployment/restart. The counterpart Biospherical Expansion page shows its incoming Noisiumed warning. `ss` confirms research port 8081 binds only to loopback. Both LAN Caddy and public HTTPS ingress return 404 for research paths even with the valid bearer token and forged forwarding headers. These ingress probes ran from the workstation; an independent off-LAN probe was unavailable (web retrieval could not access the site), so that external-vantage acceptance item remains pending. Phone-width browser evidence also remains pending. The public listener's connector check provides isolation independently of proxy headers. Direct LAN gateway access, automatic refreshes, and scheduled updates were not enabled.

Research import files are preserved under `catalogue/imports/2026-10-22-request-*.json`. Re-uploading unchanged files was verified to perform no writes. Branch parity and maintenance remain UNKNOWN unless specifically established; a matching build or filename is not proof. The supplied ServerCore disabled filename is recorded as inventory context without enabling the mod. More Culling, Oculus, and Custom NPCs forks are identified separately. The Modern Technology pack's conflicting header/recent-file loader labels are noted instead of hidden.
