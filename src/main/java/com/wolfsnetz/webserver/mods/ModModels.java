package com.wolfsnetz.webserver.mods;

import java.util.List;

public final class ModModels {
    private ModModels() {}
    public enum Loader { FORGE, NEOFORGE, FABRIC, QUILT }
    public enum Availability { UNKNOWN, RELEASED, ANNOUNCED, NO_BUILD_FOUND }
    public enum Maintenance { UNKNOWN, ACTIVE, FIXES_ONLY, DISCONTINUED }
    public enum Parity { UNKNOWN, EQUIVALENT, PARTIAL, DIVERGENT }
    public enum RelationType { REQUIRED_DEPENDENCY, OPTIONAL_DEPENDENCY, ADDON, FORK, SUCCESSOR, ALTERNATIVE, LOADER_EQUIVALENT, INCOMPATIBILITY }
    public enum Platform { CURSEFORGE, MODRINTH }
    public record Target(String minecraftVersion, Loader loader) {}
    public record Evidence(String id, String url, String claim, String summary, String checkedAt, boolean uncertain) {}
    public record Listing(Platform platform, String platformId, String url) {}
    public record Release(String platformReleaseId, Platform platform, String modVersion, String channel,
                          String publishedAt, String url) {}
    public record Support(Target target, Availability availability, Maintenance maintenance, String notes,
                          String checkedAt, List<String> evidenceIds, List<Release> releases) {}
    public record Relationship(String relatedProjectId, RelationType type, Target target, String notes,
                               List<String> evidenceIds) {}
    public record Project(String id, String name, String description, List<Listing> listings,
                          List<Support> support, List<Relationship> relationships) {}
    public record Branch(String projectId, Target target) {}
    public record Comparison(String id, Branch left, Branch right, Parity parity, String notes,
                             String checkedAt, List<String> evidenceIds) {}
    public record Family(String id, String name, String description, List<String> aliases, List<String> tags,
                         boolean published, boolean archived, List<Project> projects,
                         List<Evidence> evidence, List<Comparison> comparisons) {}
    public record Change(long expectedRevision, Family family) {}
    public record Batch(String requestId, String reason, List<Change> changes) {}
    public record Entry(long revision, String updatedAt, Family family) {}
    public record BatchResult(String requestId, boolean dryRun, List<Entry> entries) {}
    public record Page(int offset, int limit, long total, List<Entry> entries) {
        public int previousOffset() { return Math.max(0, offset - limit); }
        public long endOffset() { return Math.min((long) offset + limit, total); }
    }
    public record History(long revision, String actor, String changedAt, String reason,
                          String requestId, Family family) {}
}
