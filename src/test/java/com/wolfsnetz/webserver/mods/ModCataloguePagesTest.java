package com.wolfsnetz.webserver.mods;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"app.display-name=Catalogue Pages", "app.bootstrap-admin.username=admin", "app.bootstrap-admin.password=test-password"})
@AutoConfigureMockMvc
class ModCataloguePagesTest {
    private static final Path DATABASE = temporaryDatabase();
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModCatalogueService catalogue;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE); }

    @BeforeEach
    void reset() {
        for (String table : List.of("mod_requests", "mod_revisions", "mod_comparisons", "mod_evidence", "mod_relationships",
            "mod_listings", "mod_support", "mod_projects", "mod_tags", "mod_aliases", "mod_families")) { jdbc.update("DELETE FROM " + table); }
    }

    @Test
    void minecraftParentLinksToBothModsAndServer() throws Exception {
        mvc.perform(get("/games/minecraft/index.html")).andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/games/minecraft/mods\"")))
            .andExpect(content().string(containsString("Minecraft Mods")))
            .andExpect(content().string(containsString("Minecraft Server")));
        mvc.perform(get("/minecraft")).andExpect(redirectedUrl("/games/minecraft/index.html"));
    }

    @Test
    void browsingIsPublicButDraftsAndArchivesStayPrivate() throws Exception {
        seed(false, false);
        mvc.perform(get("/games/minecraft/mods")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("Catalogue demonstration"))));
        mvc.perform(get("/games/minecraft/mods/catalogue-demo")).andExpect(status().isNotFound());
        seed(true, true);
        mvc.perform(get("/games/minecraft/mods/catalogue-demo")).andExpect(status().isNotFound());
        seed(true, false);
        mvc.perform(get("/games/minecraft/mods")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Catalogue demonstration")))
            .andExpect(content().string(not(containsString("Manage mods</a>"))));
        mvc.perform(get("/games/minecraft/mods/catalogue-demo")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Example Core")))
            .andExpect(content().string(containsString("Sources &amp; verification")))
            .andExpect(content().string(containsString("Unknown parity")));
        mvc.perform(get("/games/minecraft/mods").param("q", "Reforged").param("tag", "scifi").param("loader", "NEOFORGE").param("minecraftVersion", "1.21.1"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Catalogue demonstration")));
        mvc.perform(get("/games/minecraft/mods").param("bothPrimary", "true"))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("Catalogue demonstration"))));
    }

    @Test
    void adminPagesRequireAdminRoleAndRenderAllTools() throws Exception {
        seed(false, false);
        for (String path : List.of("/admin/mods", "/admin/mods/new", "/admin/mods/import", "/admin/mods/catalogue-demo/edit",
            "/admin/mods/catalogue-demo/preview", "/admin/mods/catalogue-demo/history", "/admin/mods/api/export")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
            mvc.perform(get(path).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("curator").roles("ADMIN"))).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/mods/catalogue-demo/edit").with(user("curator").roles("ADMIN")))
            .andExpect(content().string(containsString("csrf-token")))
            .andExpect(content().string(containsString("data-revision=\"1\"")));
    }

    @Test
    void publicAndAdminPaginationRenderWithMoreThanTwelveFamilies() throws Exception {
        var changes = new java.util.ArrayList<ModModels.Change>();
        for (int i = 0; i < 13; i++) {
            var project = new ModModels.Project("page-project-" + i, "Project " + i, "Synthetic project", List.of(), List.of(), List.of());
            var family = new ModModels.Family("page-family-" + i, "Family " + i, "Synthetic family", List.of(), List.of(),
                true, false, List.of(project), List.of(), List.of());
            changes.add(new ModModels.Change(0, family));
        }
        catalogue.apply(new ModModels.Batch("pagination-seed", "Verify real pagination", changes), false, "test");
        mvc.perform(get("/games/minecraft/mods")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Showing 1–12 of 13")));
        mvc.perform(get("/games/minecraft/mods").param("offset", "12")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Showing 13–13 of 13")));
        mvc.perform(get("/admin/mods").with(user("curator").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Showing 1–12 of 13")));
    }

    @Test
    void adminWritesNeedCsrfAndUseTheActualUserAsActor() throws Exception {
        String body = fixture().toString();
        mvc.perform(post("/admin/mods/api/batches").with(user("curator").roles("ADMIN")).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/mods/api/batches").with(user("reader").roles("USER")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/mods/api/batches").with(user("curator").roles("ADMIN")).with(csrf()).param("dryRun", "true").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mod_families", Integer.class));
        mvc.perform(post("/admin/mods/api/batches").with(user("curator").roles("ADMIN")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk());
        assertEquals("curator", catalogue.history("catalogue-demo", 0, 25).get(0).actor());
    }

    @Test
    void adminConflictsPreserveRecordsAndRestoreAppendsHistory() throws Exception {
        seed(true, false);
        seed(true, true);
        mvc.perform(post("/admin/mods/api/batches").with(user("curator").roles("ADMIN")).with(csrf()).contentType("application/json").content(fixture().toString()))
            .andExpect(status().isConflict());
        mvc.perform(post("/admin/mods/catalogue-demo/restore").with(user("curator").roles("ADMIN")).with(csrf())
            .param("revision", "1").param("expectedRevision", "2").param("reason", "Undo accidental archive"))
            .andExpect(redirectedUrl("/admin/mods/catalogue-demo/history"));
        assertEquals(3, catalogue.get("catalogue-demo").revision());
        assertFalse(catalogue.get("catalogue-demo").family().archived());
        assertEquals("curator", catalogue.history("catalogue-demo", 0, 25).get(0).actor());
        mvc.perform(post("/admin/mods/catalogue-demo/restore").with(user("curator").roles("ADMIN")).with(csrf())
            .param("revision", "1").param("expectedRevision", "2").param("reason", "Stale history page"))
            .andExpect(flash().attribute("error", containsString("Revision conflict")));
        assertEquals(3, catalogue.get("catalogue-demo").revision());
    }

    private void seed(boolean published, boolean archived) throws Exception {
        ObjectNode batch = fixture();
        batch.put("requestId", java.util.UUID.randomUUID().toString());
        long revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM mod_families", Long.class);
        ((ObjectNode) batch.path("changes").path(0)).put("expectedRevision", revision);
        ((ObjectNode) batch.path("changes").path(0).path("family")).put("published", published).put("archived", archived);
        catalogue.apply(catalogue.parseBatch(batch.toString()), false, "test-researcher");
    }
    private ObjectNode fixture() throws Exception { return (ObjectNode) json.readTree(Files.readString(Path.of("catalogue/examples/demo-batch.json"))); }
    private static Path temporaryDatabase() {
        try { return Files.createTempDirectory("mod-pages-tests-").resolve("catalogue.sqlite"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
