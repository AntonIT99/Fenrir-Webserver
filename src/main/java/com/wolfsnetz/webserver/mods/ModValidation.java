package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.net.URI;
import java.time.LocalDate;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ModValidation {
    private ModValidation() {}

    static void batch(Batch batch) {
        require(batch != null, "Batch is required");
        id(batch.requestId(), "requestId");
        text(batch.reason(), 2000, "reason");
        list(batch.changes(), 20, "changes");
        require(!batch.changes().isEmpty(), "At least one change is required");
        Set<String> families = new HashSet<>();
        Set<String> projects = new HashSet<>();
        for (Change change : batch.changes()) {
            require(change.expectedRevision() >= 0, "expectedRevision must be non-negative");
            family(change.family());
            require(families.add(change.family().id()), "Duplicate family in batch");
            for (Project project : change.family().projects()) {
                require(projects.add(project.id()), "Duplicate project in batch");
            }
        }
    }

    private static void family(Family family) {
        require(family != null, "family is required");
        id(family.id(), "family.id");
        text(family.name(), 200, "name");
        text(family.description(), 12000, "description");
        list(family.aliases(), 50, "aliases");
        unique(family.aliases(), s -> s.toLowerCase(java.util.Locale.ROOT), "aliases");
        family.aliases().forEach(s -> text(s, 200, "alias"));
        list(family.tags(), 50, "tags");
        unique(family.tags(), Function.identity(), "tags");
        family.tags().forEach(s -> id(s, "tag"));
        list(family.evidence(), 200, "evidence");
        unique(family.evidence(), Evidence::id, "evidence IDs");
        Set<String> sources = new HashSet<>();
        for (Evidence evidence : family.evidence()) {
            id(evidence.id(), "evidence.id");
            sources.add(evidence.id());
            url(evidence.url());
            text(evidence.claim(), 1000, "claim");
            text(evidence.summary(), 4000, "evidence summary");
            date(evidence.checkedAt());
        }
        list(family.projects(), 50, "projects");
        require(!family.projects().isEmpty(), "At least one project is required");
        unique(family.projects(), Project::id, "project IDs");
        for (Project project : family.projects()) {
            id(project.id(), "project.id");
            text(project.name(), 200, "project name");
            text(project.description(), 12000, "project description");
            list(project.listings(), 2, "listings");
            unique(project.listings(), Listing::platform, "platforms");
            for (Listing listing : project.listings()) {
                require(listing.platform() != null, "platform is required");
                text(listing.platformId(), 100, "platformId");
                URI uri = url(listing.url());
                String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
                require(listing.platform() == Platform.CURSEFORGE
                    ? Set.of("www.curseforge.com", "curseforge.com").contains(host)
                    : host.equals("modrinth.com"), "Listing URL does not match its platform");
            }
            list(project.support(), 100, "support");
            unique(project.support(), Support::target, "support targets");
            for (Support support : project.support()) {
                target(support.target());
                require(support.availability() != null && support.maintenance() != null, "Support statuses are required");
                note(support.notes());
                refs(support.evidenceIds(), sources);
                if (support.availability() != Availability.UNKNOWN || support.maintenance() != Maintenance.UNKNOWN) {
                    date(support.checkedAt());
                    require(!support.evidenceIds().isEmpty(), "Support claims need evidence");
                } else if (support.checkedAt() != null) {
                    date(support.checkedAt());
                }
                list(support.releases(), 50, "releases");
                require(support.releases().isEmpty() || support.availability() == Availability.RELEASED,
                    "Release records require RELEASED availability");
                for (Release release : support.releases()) {
                    require(release.platform() != null, "Release platform is required");
                    text(release.platformReleaseId(), 200, "platformReleaseId");
                    text(release.modVersion(), 200, "modVersion");
                    require(release.channel() != null && Set.of("RELEASE", "BETA", "ALPHA").contains(release.channel()), "Invalid release channel");
                    try { Instant.parse(release.publishedAt()); }
                    catch (RuntimeException e) { throw invalid("publishedAt must be an ISO instant"); }
                    url(release.url());
                }
            }
            list(project.relationships(), 100, "relationships");
            unique(project.relationships(), r -> r.relatedProjectId() + ":" + r.type() + ":" + r.target(), "relationships");
            for (Relationship relationship : project.relationships()) {
                id(relationship.relatedProjectId(), "relatedProjectId");
                require(!project.id().equals(relationship.relatedProjectId()), "Self relationships are not supported");
                require(relationship.type() != null, "Relationship type is required");
                if (relationship.target() != null) {
                    target(relationship.target());
                    require(project.support().stream().anyMatch(s -> s.target().equals(relationship.target())),
                        "Scoped relationship requires a source support record");
                }
                note(relationship.notes());
                refs(relationship.evidenceIds(), sources);
                require(!relationship.evidenceIds().isEmpty(), "Relationships need evidence");
                if (relationship.type() == RelationType.INCOMPATIBILITY) {
                    require(!relationship.notes().isBlank(), "Confirmed incompatibilities need scope and a failure description");
                    require(family.evidence().stream().anyMatch(e -> relationship.evidenceIds().contains(e.id()) && !e.uncertain()),
                        "Confirmed incompatibilities need at least one verified source");
                }
            }
        }
        list(family.comparisons(), 100, "comparisons");
        unique(family.comparisons(), Comparison::id, "comparison IDs");
        for (Comparison comparison : family.comparisons()) {
            id(comparison.id(), "comparison.id");
            branch(comparison.left(), family);
            branch(comparison.right(), family);
            require(!comparison.left().equals(comparison.right()), "Compare two distinct branches");
            require(comparison.parity() != null, "parity is required");
            note(comparison.notes());
            refs(comparison.evidenceIds(), sources);
            if (comparison.parity() != Parity.UNKNOWN) {
                date(comparison.checkedAt());
                require(!comparison.evidenceIds().isEmpty(), "Parity claims need evidence");
            } else if (comparison.checkedAt() != null) { date(comparison.checkedAt()); }
        }
    }

    private static void branch(Branch branch, Family family) {
        require(branch != null, "Comparison branch is required");
        target(branch.target());
        require(family.projects().stream().anyMatch(p -> p.id().equals(branch.projectId())
            && p.support().stream().anyMatch(s -> s.target().equals(branch.target()))),
            "Comparison branch must reference a project and support record in this family");
    }

    static void target(Target target) {
        require(target != null && target.loader() != null, "Exact version and loader are required");
        require(target.minecraftVersion() != null && target.minecraftVersion().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"),
            "Use an exact numeric Minecraft version, not a future wildcard");
        require(target.minecraftVersion().length() <= 30, "Minecraft version is too long");
    }

    private static void refs(List<String> refs, Set<String> evidence) {
        list(refs, 50, "evidenceIds");
        unique(refs, Function.identity(), "evidence references");
        require(evidence.containsAll(refs), "Unknown evidence reference");
    }

    private static URI url(String value) {
        text(value, 2000, "URL");
        try {
            URI uri = URI.create(value);
            require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null, "URLs must use HTTPS without credentials");
            return uri;
        } catch (IllegalArgumentException e) { throw invalid("Invalid URL"); }
    }

    private static void date(String value) {
        try { LocalDate.parse(value); }
        catch (RuntimeException e) { throw invalid("checkedAt must be an ISO date (YYYY-MM-DD)"); }
    }

    private static void id(String value, String field) {
        require(value != null && value.matches("[a-z0-9][a-z0-9_-]{0,99}"), "Invalid " + field);
    }

    private static void text(String value, int max, String field) {
        require(value != null && !value.isBlank() && value.length() <= max, "Invalid " + field);
    }

    private static void note(String value) { require(value != null && value.length() <= 6000, "Invalid notes"); }

    private static <T> void list(List<T> values, int max, String field) {
        require(values != null && values.size() <= max && values.stream().noneMatch(java.util.Objects::isNull), "Invalid " + field);
    }

    private static <T, K> void unique(List<T> values, Function<T, K> key, String field) {
        Set<K> seen = new HashSet<>();
        require(values.stream().allMatch(v -> seen.add(key.apply(v))), "Duplicate " + field);
    }

    static void require(boolean condition, String message) { if (!condition) { throw invalid(message); } }
    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
