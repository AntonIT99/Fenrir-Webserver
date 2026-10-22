package com.wolfsnetz.webserver.mods;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.display-name=Catalogue Test", "app.bootstrap-admin.username=admin", "app.bootstrap-admin.password=test-password",
    "mods.api.enabled=true", "server.forward-headers-strategy=NONE"
})
class ModCatalogueIntegrationTest {
    private static final String TOKEN = "test-only-research-token-with-sufficient-entropy";
    private static final int INTERNAL_PORT = freePort();
    private static final Path DATABASE = temporaryDatabase();
    private static final String PREFIX = ModApiSecurity.PREFIX;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort private int websitePort;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModCatalogueMigrations migrations;
    @Autowired private ModCatalogueService catalogue;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("mods.api.port", () -> INTERNAL_PORT);
        registry.add("mods.api.token-sha256", () -> HexFormat.of().formatHex(ModApiSecurity.sha256(TOKEN)));
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @BeforeEach
    void cleanCatalogue() {
        for (String table : List.of("mod_requests", "mod_revisions", "mod_comparisons", "mod_evidence", "mod_relationships",
            "mod_listings", "mod_support", "mod_projects", "mod_tags", "mod_aliases", "mod_families")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void connectorIsolationHoldsEvenWithCredentialsAndSpoofedHeaders() throws Exception {
        assertEquals(200, get(INTERNAL_PORT, PREFIX + "/targets", TOKEN).statusCode());
        assertEquals(401, get(INTERNAL_PORT, PREFIX + "/targets", null).statusCode());
        assertEquals(401, get(INTERNAL_PORT, PREFIX + "/targets", "revoked-token").statusCode());
        assertEquals(404, get(INTERNAL_PORT, "/login", TOKEN).statusCode());
        var spoof = HttpRequest.newBuilder(url(websitePort, PREFIX + "/targets"))
            .header("Authorization", "Bearer " + TOKEN)
            .header("X-Forwarded-For", "127.0.0.1").header("X-Forwarded-Port", String.valueOf(INTERNAL_PORT))
            .header("Forwarded", "for=127.0.0.1;host=127.0.0.1:" + INTERNAL_PORT).GET().build();
        assertEquals(404, client.send(spoof, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, get(websitePort, "/login", null).statusCode());
        assertTrue(get(INTERNAL_PORT, PREFIX + "/targets", TOKEN).headers().allValues("set-cookie").isEmpty());
    }

    @Test
    void previewCommitRetrySearchAndAuditRoundTrip() throws Exception {
        String body = fixture().toString();
        var preview = post(body, true);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(json.readTree(preview.body()).path("dryRun").asBoolean());
        assertEquals(0, count("mod_families"));
        assertEquals(0, count("mod_requests"));
        var committed = post(body, false);
        assertEquals(200, committed.statusCode(), committed.body());
        assertEquals(committed.body(), post(body, false).body());
        assertEquals(1, count("mod_revisions"));
        assertEquals(2, count("mod_projects"));
        assertEquals(1, count("mod_relationships"));
        var found = get(INTERNAL_PORT, PREFIX + "/families?q=Reforged&tag=scifi&minecraftVersion=1.21.1&loader=NEOFORGE", TOKEN);
        assertEquals(1, json.readTree(found.body()).path("total").asInt(), found.body());
        var entry = get(INTERNAL_PORT, PREFIX + "/families/catalogue-demo", TOKEN);
        assertEquals(1, json.readTree(entry.body()).path("revision").asInt());
        assertEquals("UNKNOWN", json.readTree(entry.body()).path("family").path("comparisons").path(0).path("parity").asText());
        var history = get(INTERNAL_PORT, PREFIX + "/families/catalogue-demo/history", TOKEN);
        assertEquals("catalogue-research", json.readTree(history.body()).path(0).path("actor").asText());
        assertEquals(200, get(INTERNAL_PORT, PREFIX + "/export?includeArchived=true", TOKEN).statusCode());
    }

    @Test
    void updatesRequireRevisionAndRetryIdsAreBoundToContent() throws Exception {
        ObjectNode original = fixture();
        assertEquals(200, post(original.toString(), false).statusCode());
        ObjectNode update = fixture();
        update.put("requestId", "demo-update");
        ((ObjectNode) update.path("changes").path(0)).put("expectedRevision", 1);
        ((ObjectNode) update.path("changes").path(0).path("family")).put("name", "Updated demonstration");
        assertEquals(200, post(update.toString(), false).statusCode());
        assertEquals(2, catalogue.get("catalogue-demo").revision());
        assertEquals(2, catalogue.history("catalogue-demo", 0, 25).size());
        update.put("requestId", "stale-update");
        assertEquals(409, post(update.toString(), false).statusCode());
        assertEquals(2, count("mod_revisions"));
        original.put("reason", "Different content for the same retry ID");
        assertEquals(409, post(original.toString(), false).statusCode());
        assertEquals(2, count("mod_requests"));
    }

    @Test
    void invalidSecondFamilyRollsBackTheWholeBatch() throws Exception {
        ObjectNode batch = fixture();
        ObjectNode second = (ObjectNode) batch.path("changes").path(0).deepCopy();
        ObjectNode family = (ObjectNode) second.path("family");
        family.put("id", "second-demo");
        ((ObjectNode) family.path("projects").path(0)).put("id", "second-machines");
        ((ObjectNode) family.path("projects").path(1)).put("id", "second-core");
        ((ObjectNode) family.path("comparisons").path(0).path("left")).put("projectId", "second-machines");
        ((ObjectNode) family.path("comparisons").path(0).path("right")).put("projectId", "second-machines");
        ((ObjectNode) family.path("projects").path(0).path("relationships").path(0)).put("relatedProjectId", "missing-project");
        ((tools.jackson.databind.node.ArrayNode) batch.path("changes")).add(second);
        var response = post(batch.toString(), false);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(0, count("mod_families"));
        assertEquals(0, count("mod_projects"));
        assertEquals(0, count("mod_requests"));
        assertEquals(0, count("mod_revisions"));
    }

    @Test
    void rejectsInventedAvailabilityMissingEvidenceAndUnknownFields() throws Exception {
        ObjectNode batch = fixture();
        ObjectNode support = (ObjectNode) batch.path("changes").path(0).path("family").path("projects").path(0).path("support").path(0);
        support.put("availability", "RELEASED");
        assertEquals(400, post(batch.toString(), false).statusCode());
        batch = fixture();
        ((ObjectNode) batch.path("changes").path(0).path("family").path("projects").path(0).path("relationships").path(0))
            .putArray("evidenceIds").add("missing-source");
        assertEquals(400, post(batch.toString(), false).statusCode());
        batch = fixture();
        batch.put("mistypedField", "must not be silently ignored");
        assertEquals(400, post(batch.toString(), false).statusCode());
        assertEquals(400, post("{", false).statusCode());
        assertEquals(0, count("mod_families"));
    }

    @Test
    void archiveAndRestoreCreateNewRevisions() throws Exception {
        assertEquals(200, post(fixture().toString(), false).statusCode());
        ObjectNode batch = fixture();
        batch.put("requestId", "archive-demo");
        ((ObjectNode) batch.path("changes").path(0)).put("expectedRevision", 1);
        ((ObjectNode) batch.path("changes").path(0).path("family")).put("archived", true);
        assertEquals(200, post(batch.toString(), false).statusCode());
        assertEquals(0, json.readTree(get(INTERNAL_PORT, PREFIX + "/families", TOKEN).body()).path("total").asInt());
        assertEquals(1, json.readTree(get(INTERNAL_PORT, PREFIX + "/families?includeArchived=true", TOKEN).body()).path("total").asInt());
        batch.put("requestId", "restore-demo");
        ((ObjectNode) batch.path("changes").path(0)).put("expectedRevision", 2);
        ((ObjectNode) batch.path("changes").path(0).path("family")).put("archived", false);
        assertEquals(200, post(batch.toString(), false).statusCode());
        assertEquals(3, catalogue.get("catalogue-demo").revision());
    }

    @Test
    void migrationsAreRepeatablePreserveUsersAndEnforceForeignKeys() throws Exception {
        int users = count("users");
        assertTrue(users > 0);
        assertEquals(200, post(fixture().toString(), false).statusCode());
        migrations.afterPropertiesSet();
        assertEquals(users, count("users"));
        assertEquals(1, count("mod_schema_versions"));
        assertEquals(1, count("mod_families"));
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
            () -> jdbc.update("INSERT INTO mod_aliases VALUES ('missing-family', 'orphan')"));
        var secondConnection = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + DATABASE);
        assertEquals(1, new JdbcTemplate(secondConnection).queryForObject("SELECT count(*) FROM mod_families", Integer.class));
    }

    @Test
    void rejectsOversizeChunkedBodiesAndUnboundedPagination() throws Exception {
        var request = HttpRequest.newBuilder(url(INTERNAL_PORT, PREFIX + "/batches"))
            .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[1_048_577]))).build();
        assertEquals(413, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, get(INTERNAL_PORT, PREFIX + "/families?limit=1000", TOKEN).statusCode());
    }

    @Test
    void duplicatePlatformIdsAndRemovedProjectIdsCannotCorruptTheCatalogue() throws Exception {
        ObjectNode batch = fixture();
        ObjectNode family = (ObjectNode) batch.path("changes").path(0).path("family");
        ObjectNode firstListing = json.createObjectNode();
        firstListing.put("platform", "MODRINTH").put("platformId", "example-id").put("url", "https://modrinth.com/mod/example");
        ((tools.jackson.databind.node.ArrayNode) family.path("projects").path(0).path("listings")).add(firstListing);
        ((tools.jackson.databind.node.ArrayNode) family.path("projects").path(1).path("listings")).add(firstListing.deepCopy());
        assertEquals(409, post(batch.toString(), false).statusCode());
        assertEquals(0, count("mod_families"));
        assertEquals(200, post(fixture().toString(), false).statusCode());
        batch = fixture();
        batch.put("requestId", "remove-core");
        ((ObjectNode) batch.path("changes").path(0)).put("expectedRevision", 1);
        ((tools.jackson.databind.node.ArrayNode) batch.path("changes").path(0).path("family").path("projects")).remove(1);
        assertEquals(400, post(batch.toString(), false).statusCode());
        assertEquals(2, count("mod_projects"));
        assertEquals(1, count("mod_revisions"));
        assertEquals(0, json.readTree(get(INTERNAL_PORT, PREFIX + "/families?q=%25", TOKEN).body()).path("total").asInt());
        assertEquals(400, get(INTERNAL_PORT, PREFIX + "/families?loader=UNKNOWN_LOADER", TOKEN).statusCode());
    }

    @Test
    void availabilityDoesNotRequirePersistingExactModReleaseVersions() throws Exception {
        ObjectNode batch = fixture();
        ObjectNode support = (ObjectNode) batch.path("changes").path(0).path("family").path("projects").path(0).path("support").path(0);
        support.put("availability", "RELEASED").put("checkedAt", "2026-10-22");
        support.putArray("evidenceIds").add("fixture-notice");
        assertEquals(200, post(batch.toString(), false).statusCode());
        assertTrue(catalogue.get("catalogue-demo").family().projects().get(0).support().get(0).releases().isEmpty());
    }

    @Test
    void confirmedIncompatibilitiesNeedVerifiedEvidenceAndAppearFromEitherFamily() throws Exception {
        ObjectNode batch = fixture();
        ObjectNode family = (ObjectNode) batch.path("changes").path(0).path("family");
        family.put("published", true);
        ObjectNode relation = (ObjectNode) family.path("projects").path(0).path("relationships").path(0);
        relation.put("type", "INCOMPATIBILITY");
        assertEquals(400, post(batch.toString(), false).statusCode());
        ((ObjectNode) family.path("evidence").path(0)).put("uncertain", false);
        ObjectNode secondChange = (ObjectNode) batch.path("changes").path(0).deepCopy();
        ObjectNode other = (ObjectNode) secondChange.path("family");
        other.put("id", "other-family");
        other.putArray("comparisons");
        other.putArray("projects").add(json.createObjectNode().put("id", "other-project").put("name", "Other renderer")
            .put("description", "Synthetic incompatibility target").set("listings", json.createArrayNode()));
        ObjectNode otherProject = (ObjectNode) other.path("projects").path(0);
        otherProject.putArray("support");
        otherProject.putArray("relationships");
        relation.put("relatedProjectId", "other-project");
        ((tools.jackson.databind.node.ArrayNode) batch.path("changes")).add(secondChange);
        assertEquals(200, post(batch.toString(), false).statusCode());
        assertEquals(1, catalogue.incompatibilities(catalogue.get("catalogue-demo").family(), true).size());
        assertEquals(1, catalogue.incompatibilities(catalogue.get("other-family").family(), true).size());
    }

    @Test
    void incompleteSecurityConfigurationCannotEnableTheListener() {
        assertThrows(IllegalStateException.class, () -> new ModApiSecurity(true, 8081, "", 8080, "NONE"));
        String hash = HexFormat.of().formatHex(ModApiSecurity.sha256(TOKEN));
        assertThrows(IllegalStateException.class, () -> new ModApiSecurity(true, 8080, hash, 8080, "NONE"));
        assertThrows(IllegalStateException.class, () -> new ModApiSecurity(true, 8081, hash, 8080, "FRAMEWORK"));
        assertDoesNotThrow(() -> new ModApiSecurity(false, 8081, "", 8080, "NONE"));
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(Path.of("catalogue/examples/demo-batch.json")));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private URI url(int port, String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(url(port, path)).timeout(Duration.ofSeconds(10)).GET();
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String body, boolean dryRun) throws Exception {
        return client.send(HttpRequest.newBuilder(url(INTERNAL_PORT, PREFIX + "/batches?dryRun=" + dryRun))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Path temporaryDatabase() {
        try { return Files.createTempDirectory("mod-catalogue-tests-").resolve("catalogue.sqlite"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
