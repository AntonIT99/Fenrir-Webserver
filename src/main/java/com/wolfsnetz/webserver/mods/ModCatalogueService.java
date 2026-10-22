package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@DependsOn("modCatalogueMigrations")
public class ModCatalogueService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ModCatalogueService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Batch parseBatch(String body) {
        try { return json.readValue(body, Batch.class); }
        catch (tools.jackson.core.JacksonException error) { throw ModValidation.invalid("Invalid JSON or field value"); }
    }

    public Entry get(String id) {
        var rows = jdbc.query("SELECT revision, updated_at, document FROM mod_families WHERE id = ?",
            (rs, row) -> new Entry(rs.getLong(1), rs.getString(2), json.readValue(rs.getString(3), Family.class)), id);
        if (rows.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"); }
        return rows.get(0);
    }

    public Page search(String query, String tag, String version, Loader loader, Availability availability,
                       boolean includeArchived, int offset, int limit) {
        return search(query, tag, version, loader, availability, null, includeArchived, false, false, offset, limit);
    }

    public Page search(String query, String tag, String version, Loader loader, Availability availability,
                       Maintenance maintenance, boolean includeArchived, boolean publishedOnly, boolean bothPrimary,
                       int offset, int limit) {
        ModValidation.require(offset >= 0 && limit >= 1 && limit <= 100, "Use offset >= 0 and limit 1–100");
        ModValidation.require(query.length() <= 200 && (tag == null || tag.length() <= 100)
            && (version == null || version.length() <= 30), "Search parameter too long");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!includeArchived) { where.append(" AND f.archived = 0"); }
        if (publishedOnly) { where.append(" AND f.published = 1 AND f.archived = 0"); }
        if (bothPrimary) {
            for (Target target : List.of(new Target("1.20.1", Loader.FORGE), new Target("1.21.1", Loader.NEOFORGE))) {
                where.append(" AND EXISTS (SELECT 1 FROM mod_support s JOIN mod_projects p ON p.id=s.project_id"
                    + " WHERE p.family_id=f.id AND s.minecraft_version=? AND s.loader=? AND s.availability='RELEASED')");
                args.add(target.minecraftVersion());
                args.add(target.loader().name());
            }
        }
        if (!query.isBlank()) {
            where.append(" AND (f.name LIKE ? ESCAPE '\\' OR f.description LIKE ? ESCAPE '\\'"
                + " OR EXISTS (SELECT 1 FROM mod_aliases a WHERE a.family_id=f.id AND a.alias LIKE ? ESCAPE '\\')"
                + " OR EXISTS (SELECT 1 FROM mod_projects p WHERE p.family_id=f.id AND p.name LIKE ? ESCAPE '\\'))");
            String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) { args.add(pattern); }
        }
        if (tag != null) {
            where.append(" AND EXISTS (SELECT 1 FROM mod_tags t WHERE t.family_id=f.id AND t.tag=?)");
            args.add(tag);
        }
        if (version != null || loader != null || availability != null || maintenance != null) {
            where.append(" AND EXISTS (SELECT 1 FROM mod_support s JOIN mod_projects p ON p.id=s.project_id WHERE p.family_id=f.id");
            if (version != null) { where.append(" AND s.minecraft_version=?"); args.add(version); }
            if (loader != null) { where.append(" AND s.loader=?"); args.add(loader.name()); }
            if (availability != null) { where.append(" AND s.availability=?"); args.add(availability.name()); }
            if (maintenance != null) { where.append(" AND s.maintenance=?"); args.add(maintenance.name()); }
            where.append(")");
        }
        long count = jdbc.queryForObject("SELECT count(*) FROM mod_families f" + where, Long.class, args.toArray());
        args.add(limit);
        args.add(offset);
        var entries = jdbc.query("SELECT f.revision, f.updated_at, f.document FROM mod_families f" + where
            + " ORDER BY f.name COLLATE NOCASE, f.id LIMIT ? OFFSET ?",
            (rs, row) -> new Entry(rs.getLong(1), rs.getString(2), json.readValue(rs.getString(3), Family.class)), args.toArray());
        return new Page(offset, limit, count, entries);
    }

    public Entry published(String id) {
        Entry entry = get(id);
        if (!entry.family().published() || entry.family().archived()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found");
        }
        return entry;
    }

    public List<String> tags(boolean publishedOnly) {
        return jdbc.queryForList("SELECT DISTINCT t.tag FROM mod_tags t JOIN mod_families f ON f.id=t.family_id"
            + (publishedOnly ? " WHERE f.published=1 AND f.archived=0" : "") + " ORDER BY t.tag", String.class);
    }

    public record ProjectLink(String name, String familyId, String projectId) {}

    public record Incompatibility(String source, String other, Target target, String notes, List<Evidence> evidence) {}

    public List<Incompatibility> incompatibilities(Family family, boolean publishedOnly) {
        return jdbc.query("SELECT p.name, q.name, r.document, f.document FROM mod_relationships r"
            + " JOIN mod_projects p ON p.id=r.project_id JOIN mod_projects q ON q.id=r.related_project_id"
            + " JOIN mod_families f ON f.id=p.family_id JOIN mod_families g ON g.id=q.family_id"
            + " WHERE r.type='INCOMPATIBILITY' AND (p.family_id=? OR q.family_id=?)"
            + (publishedOnly ? " AND f.published=1 AND f.archived=0 AND g.published=1 AND g.archived=0" : ""),
            (rs, row) -> {
                Relationship relation = json.readValue(rs.getString(3), Relationship.class);
                Family owner = json.readValue(rs.getString(4), Family.class);
                return new Incompatibility(rs.getString(1), rs.getString(2), relation.target(), relation.notes(),
                    owner.evidence().stream().filter(e -> relation.evidenceIds().contains(e.id())).toList());
            }, family.id(), family.id());
    }

    public Map<String, ProjectLink> projectLinks(Family family, boolean publishedOnly) {
        Map<String, ProjectLink> links = new LinkedHashMap<>();
        for (Project project : family.projects()) {
            for (Relationship relation : project.relationships()) {
                var found = jdbc.query("SELECT p.name, p.family_id, p.id FROM mod_projects p JOIN mod_families f ON f.id=p.family_id"
                    + " WHERE p.id=?" + (publishedOnly ? " AND f.published=1 AND f.archived=0" : ""),
                    (rs, row) -> new ProjectLink(rs.getString(1), rs.getString(2), rs.getString(3)), relation.relatedProjectId());
                if (!found.isEmpty()) { links.put(relation.relatedProjectId(), found.get(0)); }
            }
        }
        return links;
    }

    public History revision(String id, long revision) {
        var rows = jdbc.query("SELECT actor, changed_at, reason, request_id, document FROM mod_revisions WHERE family_id=? AND revision=?",
            (rs, row) -> new History(revision, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                json.readValue(rs.getString(5), Family.class)), id, revision);
        if (rows.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Revision not found"); }
        return rows.get(0);
    }

    public String familyJson(Family family) { return json.writeValueAsString(family); }

    public List<Target> targets() {
        return jdbc.query("SELECT minecraft_version, loader FROM mod_targets ORDER BY minecraft_version, loader",
            (rs, row) -> new Target(rs.getString(1), Loader.valueOf(rs.getString(2))));
    }

    public List<History> history(String id, int offset, int limit) {
        ModValidation.require(offset >= 0 && limit > 0 && limit <= 100, "Invalid history pagination");
        get(id);
        return jdbc.query("SELECT revision, actor, changed_at, reason, request_id, document FROM mod_revisions"
            + " WHERE family_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
            (rs, row) -> new History(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                json.readValue(rs.getString(6), Family.class)), id, limit, offset);
    }

    public BatchResult apply(Batch batch, boolean dryRun, String actor) {
        ModValidation.batch(batch);
        String hash = HexFormat.of().formatHex(ModApiSecurity.sha256(json.writeValueAsString(batch)));
        return transactions.execute(status -> {
            // The first statement takes SQLite's write lock, before any revision reads.
            int inserted = jdbc.update("INSERT INTO mod_requests (request_id, payload_hash, response, created_at)"
                + " VALUES (?, ?, '', ?) ON CONFLICT(request_id) DO NOTHING", batch.requestId(), hash, Instant.now().toString());
            if (inserted == 0) {
                var previous = jdbc.queryForMap("SELECT payload_hash, response FROM mod_requests WHERE request_id=?", batch.requestId());
                conflict(hash.equals(previous.get("payload_hash")), "requestId was already used for different content");
                if (dryRun) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This requestId has already been committed; use a new ID for a new preview");
                }
                return json.readValue((String) previous.get("response"), BatchResult.class);
            }
            String now = Instant.now().toString();
            List<Entry> entries = new ArrayList<>();
            for (Change change : batch.changes()) {
                Family family = change.family();
                var existing = jdbc.queryForList("SELECT revision FROM mod_families WHERE id=?", Long.class, family.id());
                long revision = existing.isEmpty() ? 0 : existing.get(0);
                conflict(revision == change.expectedRevision(), "Revision conflict for " + family.id());
                if (revision == 0) {
                    jdbc.update("INSERT INTO mod_families (id, name, description, published, archived, revision, document, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", family.id(), family.name(), family.description(), family.published(),
                        family.archived(), 1, json.writeValueAsString(family), now);
                } else {
                    var existingIds = jdbc.queryForList("SELECT id FROM mod_projects WHERE family_id=?", String.class, family.id());
                    ModValidation.require(family.projects().stream().map(Project::id).toList().containsAll(existingIds),
                        "Projects cannot be removed; preserve stable IDs and archive the family if needed");
                    int changed = jdbc.update("UPDATE mod_families SET name=?, description=?, published=?, archived=?, revision=?, document=?, updated_at=?"
                        + " WHERE id=? AND revision=?", family.name(), family.description(), family.published(), family.archived(), revision + 1,
                        json.writeValueAsString(family), now, family.id(), revision);
                    conflict(changed == 1, "Revision conflict for " + family.id());
                }
                for (Project project : family.projects()) {
                    var owner = jdbc.queryForList("SELECT family_id FROM mod_projects WHERE id=?", String.class, project.id());
                    conflict(owner.isEmpty() || owner.get(0).equals(family.id()), "Project ID already belongs to another family");
                    jdbc.update("INSERT INTO mod_projects (id, family_id, name, document) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, document=excluded.document",
                        project.id(), family.id(), project.name(), json.writeValueAsString(project));
                }
                entries.add(new Entry(revision + 1, now, family));
            }
            // Clear changed projections first so listings can be reassigned within one batch.
            for (Change change : batch.changes()) { clearProjections(change.family()); }
            for (Entry entry : entries) {
                project(entry.family());
                jdbc.update("INSERT INTO mod_revisions VALUES (?, ?, ?, ?, ?, ?, ?)", entry.family().id(), entry.revision(),
                    actor, now, batch.reason(), batch.requestId(), json.writeValueAsString(entry.family()));
            }
            BatchResult result = new BatchResult(batch.requestId(), dryRun, entries);
            jdbc.update("UPDATE mod_requests SET response=? WHERE request_id=?", json.writeValueAsString(result), batch.requestId());
            if (dryRun) { status.setRollbackOnly(); }
            return result;
        });
    }

    private void clearProjections(Family family) {
        for (String table : List.of("mod_aliases", "mod_tags", "mod_evidence", "mod_comparisons")) {
            jdbc.update("DELETE FROM " + table + " WHERE family_id=?", family.id());
        }
        for (String table : List.of("mod_listings", "mod_support", "mod_relationships")) {
            jdbc.update("DELETE FROM " + table + " WHERE project_id IN (SELECT id FROM mod_projects WHERE family_id=?)", family.id());
        }
    }

    private void project(Family family) {
        for (String alias : family.aliases()) { jdbc.update("INSERT INTO mod_aliases VALUES (?, ?)", family.id(), alias); }
        for (String tag : family.tags()) { jdbc.update("INSERT INTO mod_tags VALUES (?, ?)", family.id(), tag); }
        for (Evidence evidence : family.evidence()) {
            jdbc.update("INSERT INTO mod_evidence VALUES (?, ?, ?)", family.id(), evidence.id(), json.writeValueAsString(evidence));
        }
        for (Comparison comparison : family.comparisons()) {
            jdbc.update("INSERT INTO mod_comparisons VALUES (?, ?, ?)", family.id(), comparison.id(), json.writeValueAsString(comparison));
        }
        for (Project project : family.projects()) {
            for (Listing listing : project.listings()) {
                jdbc.update("INSERT INTO mod_listings VALUES (?, ?, ?, ?)", project.id(), listing.platform().name(), listing.platformId(), listing.url());
            }
            for (Support support : project.support()) {
                Target target = support.target();
                jdbc.update("INSERT INTO mod_targets VALUES (?, ?) ON CONFLICT DO NOTHING", target.minecraftVersion(), target.loader().name());
                jdbc.update("INSERT INTO mod_support VALUES (?, ?, ?, ?, ?, ?)", project.id(), target.minecraftVersion(), target.loader().name(),
                    support.availability().name(), support.maintenance().name(), json.writeValueAsString(support));
            }
            for (Relationship relationship : project.relationships()) {
                Integer exists = jdbc.queryForObject("SELECT count(*) FROM mod_projects WHERE id=?", Integer.class, relationship.relatedProjectId());
                ModValidation.require(exists != null && exists == 1, "Unknown relatedProjectId: " + relationship.relatedProjectId());
                String scope = relationship.target() == null ? "ALL" : relationship.target().minecraftVersion() + ":" + relationship.target().loader();
                jdbc.update("INSERT INTO mod_relationships VALUES (?, ?, ?, ?, ?)", project.id(), relationship.relatedProjectId(),
                    relationship.type().name(), scope, json.writeValueAsString(relationship));
            }
        }
    }

    private static void conflict(boolean condition, String message) {
        if (!condition) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
    }
}
