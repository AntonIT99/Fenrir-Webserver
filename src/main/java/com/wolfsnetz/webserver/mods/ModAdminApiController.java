package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/mods/api")
@PreAuthorize("hasRole('ADMIN')")
public class ModAdminApiController {
    private final ModCatalogueService catalogue;
    public ModAdminApiController(ModCatalogueService catalogue) { this.catalogue = catalogue; }

    @PostMapping(value = "/batches", consumes = "application/json")
    public BatchResult apply(HttpServletRequest request, @RequestParam(defaultValue = "false") boolean dryRun,
                             Principal principal) throws IOException {
        byte[] body = request.getInputStream().readNBytes(1_048_577);
        if (body.length > 1_048_576) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Batch exceeds 1 MiB"); }
        return catalogue.apply(catalogue.parseBatch(new String(body, StandardCharsets.UTF_8)), dryRun, principal.getName());
    }

    @GetMapping("/export")
    public Page export(@RequestParam(defaultValue = "0") int offset) {
        return catalogue.search("", null, null, null, null, true, offset, 100);
    }
}
