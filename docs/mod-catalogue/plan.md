# Minecraft mod catalogue implementation plan

Prepared 22 October 2026 from the supplied requirements and the current repository.

## Implementation progress

- Stage 1 complete: read-only Pi inspection, API contract, deployment diagram, and a tested SQLite backup tool. Router forwarding and external routing remain explicitly unverified.
- Stage 2 complete locally: additive schema migration, private API, transactional batches, evidence and comparisons, revision history, conflict/retry protection, paginated search/export, and a PowerShell client.
- The first end-to-end example is synthetic and unpublished. The packaged Java 17 application was exercised through the client, stopped, restarted, and verified to retain its records. Backup/restore was checked against an isolated database with committed WAL data.
- Verification: all 24 automated tests pass, including 10 new catalogue integration tests using real HTTP listeners. `test bootJar`, deployment-script syntax parsing, and whitespace checks pass.
- Security refinement: a dedicated loopback-only connector replaces the proposed shared-listener gateway secret for the first slice. Public website requests cannot reach the research API even with a valid token. Initial remote access uses an SSH local-forward; direct LAN HTTPS remains a subsequent deployment task.
- Stage 3 implemented locally: the Minecraft parent links to Mods and Server; public catalogue browsing needs no login; admin-only guided editing, previews, import/export, and history restoration use the same validated service. Drafts and archives are excluded from public browsing and detail routes.
- Latest verification: all 32 Java tests and 12 client tests pass; `test bootJar` and skill validation pass. Phone-width visual evidence remains pending because browser tools became unavailable.
- Research pilot completed for all 28 user-requested mods: 23 families, 42 projects including associated dependency/upstream records, dated evidence, and 8 confirmed scoped incompatibility relationships. Exact mod-release versions were omitted per user preference. Unverified parity/maintenance remain explicit.
- Deployed on the Pi with pre-deployment and post-import backups. Data survives restart; public browsing is anonymous, admin access requires login, and the private research port binds to loopback. LAN/public HTTPS ingress rejected authenticated research requests with forged forwarding headers. Independent off-LAN probing remains unverified.
- The compact client now defaults to the authoritative Pi database through automatic SSH forwarding, using a Windows-user-encrypted credential outside the repo. Future requested research writes there; no recurring schedule or metadata refresh was created. See [operations and API](operations.md) for the contract and operating details.

## Outcome

Build a catalogue inside the existing website that helps select mods for future modpacks. Search by names, aliases, themes, Minecraft version, and loader; inspect originals, forks, ports, dependencies, maintenance, and feature differences. Allow both manual administration and direct, authenticated research updates from a local Codex session.

The first complete release is a working catalogue with 15–20 researched mod families, an editing interface, a private research API, evidence, revision history, and a tested deployment on the Raspberry Pi.

## Confirmed starting point

- Java 17, Spring Boot 4.0.6, Spring JDBC, Thymeleaf, and SQLite are already configured.
- Existing Spring Security supports login and method security. Catalogue administration must explicitly require ADMIN; login alone is insufficient.
- `/games/**` is currently public. Public catalogue routes must contain no write operations or private research information.
- Production binds the application to `127.0.0.1:8080`. The live reverse proxy and firewall have not been inspected.
- The existing deployment script builds a JAR and manages a systemd service. It rewrites the environment file, so new API configuration must survive subsequent deployments.
- The current source initializes the users table directly; there is no versioned migration mechanism yet.
- No catalogue implementation is present in this checkout. Git status was clean before adding this plan.
- The supplied SSH destination is `pi@pi`; connectivity and the live installation remain unverified.

## Architecture

Reuse the application, database, authentication, and page styling. Put catalogue code under `com.wolfsnetz.webserver.mods`, with controllers calling shared services and repositories. Keep public pages, admin actions, and research API authentication separate, while applying the same validation and transactions to every write path.

Proposed routes:

| Route | Purpose | Access |
| --- | --- | --- |
| `/games/minecraft/mods` | Search, filters, version matrix | Public, matching the existing games section |
| `/games/minecraft/mods/{slug}` | Family details and individual projects | Public |
| `/admin/mods/**` | Editing, imports, evidence review, history | ADMIN session and CSRF protection |
| `/internal/mod-catalogue/v1/**` | Programmatic read/write and batch updates | Private ingress and dedicated token |

Public pages expose published catalogue information only. Drafts, internal notes, audit details, and credentials stay private.

### Private research access

Preferred steady-state design: a LAN-only HTTPS gateway on the Pi forwards the research API to the loopback application. The public website gateway rejects the entire internal API namespace. The private gateway binds to the actual LAN interface, and the firewall permits only the configured LAN subnet, covering IPv4 and IPv6 deliberately. Do not enable router forwarding or publish this gateway through a public tunnel.

Require a separate, revocable catalogue token on every research request. Store a token hash server-side, keep credentials outside Git, never log tokens, and give this identity no user-management permissions. Disable the API by default; incomplete security configuration must fail closed.

The application must also distinguish private ingress from public ingress. A proposed implementation uses a gateway-injected secret in addition to the client token; both gateways strip incoming copies of that header, and only the private gateway injects the configured value. Inspect the actual proxy before finalizing this mechanism. Do not trust a user-supplied `X-Forwarded-For` header or infer LAN access merely because the proxy connects from loopback.

For the first development and deployment checks, an SSH local-forward to the loopback application can provide access without opening a LAN port. This is a bootstrap option; the LAN gateway is the target for direct LAN access. Bind any client-side forward to localhost.

## Data model

| Entity | Main information |
| --- | --- |
| Families and aliases | Shared concept, searchable names, summary, member projects |
| Projects | Distinct original, port, fork, revival, or addon; description, stable ID, slug |
| Platform listings | Project, CurseForge/Modrinth ID and URL, listing status |
| Tags | Controlled thematic labels, linked to families/projects |
| Targets | Exact Minecraft version and loader; configurable and optionally archived |
| Support assessments | Project + target, availability, maintenance, checked date, evidence |
| Releases | Platform release ID, mod version, channel, publication date, supported targets |
| Comparisons | Two project/target branches, parity assessment, differences, evidence |
| Relationships | Typed project links, optionally scoped to target or release |
| Evidence | Source URL, supported claim, relevant excerpt/summary, checked date, uncertainty |
| Revisions | Actor, timestamp, reason, previous/new data, batch ID, record revision |

Keep these concepts independent:

- Availability: unknown, released, announced, or no build found as of a recorded check. A failed lookup never proves a mod does not exist.
- Maintenance: unknown, active development, fixes only, or discontinued, supported by evidence.
- Parity: unknown, equivalent, partial, or divergent, relative to a named comparison branch. Release dates and matching version numbers alone do not establish parity.
- Relationship: required dependency, optional dependency, addon, fork, successor, alternative, loader equivalent, or known incompatibility. Similar names alone do not prove a relationship.

Initial targets: Forge 1.20.1 and NeoForge 1.21.1 as primary targets, plus Forge 1.7.10 and 1.12.2 for historical context. Treat 26.x/27.x as future watch categories until exact version/loader targets are established; announced support stays distinct from downloadable releases.

Preserve platform-provided metadata separately from curated descriptions, tags, and assessments. Store platform fetch time and research verification time separately. Allow field-level or claim-level evidence so a source for availability is not presented as proof of feature parity.

## Delivery stages

### 1. Confirm deployment and define the contract

Read-only inspection over `ssh pi@pi`: service configuration, application path, database location, proxy routes, listening sockets, LAN subnet, firewall, and any public tunnel or router forwarding configuration available on the Pi. Avoid printing environment secrets.

Define the schema, enum values, JSON examples, validation rules, and API errors. Select a versioned SQL migration mechanism compatible with this project. Establish a consistent SQLite backup/restore procedure, including any journal files or a supported online backup method.

**Done when:** the deployment diagram, access rules, data contract, and backup procedure are documented, with unresolved network facts explicitly identified.

### 2. Database and private research API

Implement additive migrations that preserve users and existing data. Enable SQLite foreign-key enforcement on every connection and use transactions for related writes. Add indexes for aliases, targets, tags, platform IDs, and relationships. Keep queries bounded and paginate results.

Provide search/read, create/update, evidence, relationship, export, and transactional batch endpoints under `/internal/mod-catalogue/v1`. Prefer updating a family aggregate and its associated records consistently over exposing arbitrary database writes. Document the request format with executable examples.

Every update includes the expected record revision; stale changes return a conflict. Use stable external IDs and client request IDs to prevent duplicate creates or repeated batches. Validate relationship references, URLs, lengths, enum values, and batch size before committing. Include a dry-run validation mode. Start with archive/restore rather than permanent deletion, and record changes from both API and UI in revision history.

Create a small local client workflow that reads credentials from a protected environment or local configuration and can search, preview, submit, and verify updates without placing secrets in command history.

**Done when:** a local client can create and read a representative family, add a project and scoped dependency, update it, and export it. Tests prove rejected authentication, wrong ingress, disabled configuration, spoofed forwarding headers, revision conflicts, duplicate retries, and rollback of invalid batches. Existing login and users still work after migration.

### 3. Website catalogue and administration

Add a catalogue link to the Minecraft landing page. Build name/alias search, thematic filters, version/loader filters, and a readable support matrix. Add family pages with individual project descriptions, platform links, related projects, evidence dates, and explicit unknown states.

Provide ADMIN-only editing for the same records, batch preview/import, export, revision inspection, and restoration through a new audited revision. Keep browser writes CSRF-protected. Use lightweight JavaScript where it improves filtering; retain server-side pagination and filtering.

**Done when:** the user can find a mod by an alternative name, filter for each primary target, compare branch support, edit an entry, and review/revert a change. Anonymous users and non-admin accounts cannot mutate data. Verify the page on desktop and a narrow screen.

### 4. Research pilot and workflow validation

Populate 15–20 families representing straightforward projects, originals with revivals, divergent branches, dependencies, and loader equivalents. Start from user-nominated mods where available; choose a representative pilot if no list is provided. Research actual sources before recording any facts.

For each research request:

1. Search existing records and read their current revisions.
2. Research official project listings, release files, documentation, source repositories, and maintainer statements.
3. Write a useful description, apply themes, record exact support, and attach sources to claims.
4. Leave uncertain parity or relationships explicitly unverified.
5. Validate and submit a transaction, then read back the stored result.
6. Report additions, changes, and unresolved questions.

**Done when:** all requested fields are represented in the pilot, the common searches are useful, and a local Codex session has successfully maintained records through the private API. Fix schema or UI friction before bulk population.

### 5. Raspberry Pi deployment and acceptance

Run relevant automated tests and build the JAR. Back up the live database consistently and retain the previous JAR and configuration. Update the deployment process to preserve private API settings and token configuration. Install the private gateway/firewall rules only after confirming the actual network layout.

Verify deployed behavior through each real entry point:

- Public website: browsing works; research API paths are rejected even with a valid client token or spoofed private headers.
- LAN gateway: valid authentication works; missing/revoked tokens fail; non-allowed network access is blocked.
- Admin UI: correct roles and CSRF rules hold.
- Storage: changes survive restart; users and existing features still work; backup restoration is demonstrated against an isolated copy.

A valid public-denial check must exercise the public ingress from an external network, or equivalent independently verified routing. An application unit test alone does not establish LAN isolation. If an external check is unavailable, record that acceptance item as pending.

**Done when:** the pilot is usable on the Pi, direct research updates work from the local workstation, public isolation is verified, and rollback instructions cover both the application and database migration state.

### 6. Later enhancements

After the pilot is accepted, add platform metadata refresh with a preview/diff, bounded retry behavior, and last-successful-fetch tracking. Confirm current platform API access requirements before implementation. Fetch approved platform endpoints server-side; arbitrary evidence URLs must not trigger unrestricted server-side fetching.

Add stale-research views, broken-link checks, and refresh schedules when useful. Curated descriptions and parity assessments must survive automated refreshes. Modpack shortlists, compatibility/testing notes, and pack-specific selections can follow. Do not claim that matching Minecraft versions proves mods are compatible together.

## First implementation slice

Begin with stage 1, then implement one end-to-end example in stage 2: a family with aliases, two projects, the primary version targets, a scoped dependency, a comparison, and evidence. Deliver migrations, the protected API, a local client example, and focused tests before expanding the UI or researching hundreds of mods.

This plan was written before implementation. The progress section above records the delivered functionality and Pi deployment; the operations guide describes the current setup.
