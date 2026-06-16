package com.wolfsnetz.webserver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.display-name=Test Webserver",
    "app.bootstrap-admin.username=admin",
    "app.bootstrap-admin.password=admin123",
    "minecraft.host=127.0.0.1",
    "minecraft.port=1",
    "minecraft.timeout-ms=25",
    "skadi.rag-service.workers[0].name=test-primary",
    "skadi.rag-service.workers[0].base-url=http://127.0.0.1:1",
    "skadi.rag-service.workers[0].priority=1",
    "skadi.rag-service.connect-timeout-ms=25",
    "skadi.rag-service.request-timeout-ms=25",
    "skadi.rag-service.health-timeout-ms=25"
})
@AutoConfigureMockMvc
class SecurityRoutesTest
{
    private static final Path DATABASE = Path.of("build", "test-data", "security-routes.sqlite");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception
    {
        Files.deleteIfExists(DATABASE);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void publicRoutesDoNotRequireLogin() throws Exception
    {
        mvc.perform(get("/"))
            .andExpect(status().isOk());

        mvc.perform(get("/login"))
            .andExpect(status().isOk());

        mvc.perform(get("/register"))
            .andExpect(status().isOk());

        mvc.perform(get("/games"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/games/index.html"));

        mvc.perform(get("/games/minecraft/server/index.html"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/minecraft/status"))
            .andExpect(status().isOk());

        mvc.perform(get("/skadi"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/skadi/status"))
            .andExpect(status().isOk());
    }

    @Test
    void protectedRoutesRedirectAnonymousUsersToLogin() throws Exception
    {
        mvc.perform(get("/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));

        mvc.perform(get("/admin/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));

        mvc.perform(get("/user/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }
}
