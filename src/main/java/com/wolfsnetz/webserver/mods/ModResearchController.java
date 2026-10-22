package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(ModApiSecurity.PREFIX)
@PreAuthorize("hasRole('MOD_RESEARCH')")
public class ModResearchController {
    private final ModCatalogueService catalogue;
    public ModResearchController(ModCatalogueService catalogue) { this.catalogue = catalogue; }

    @GetMapping({"/families", "/export"})
    public Page search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String tag,
                       @RequestParam(required = false) String minecraftVersion, @RequestParam(required = false) Loader loader,
                       @RequestParam(required = false) Availability availability,
                       @RequestParam(defaultValue = "false") boolean includeArchived,
                       @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) {
        return catalogue.search(q, tag, minecraftVersion, loader, availability, includeArchived, offset, limit);
    }

    @GetMapping("/families/{id}")
    public Entry family(@PathVariable String id) { return catalogue.get(id); }

    @GetMapping("/families/{id}/history")
    public List<History> history(@PathVariable String id, @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "25") int limit) { return catalogue.history(id, offset, limit); }

    @GetMapping("/targets")
    public List<Target> targets() { return catalogue.targets(); }

    @PostMapping(value = "/batches", consumes = "application/json")
    public BatchResult apply(@RequestBody String body, @RequestParam(defaultValue = "false") boolean dryRun, Principal principal) {
        return catalogue.apply(catalogue.parseBatch(body), dryRun, principal.getName());
    }
}

@RestControllerAdvice(assignableTypes = {ModResearchController.class, ModAdminApiController.class})
class ModResearchErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("status", error.getStatusCode().value(),
            "message", error.getReason() == null ? "Request failed" : error.getReason()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(DataAccessException error) {
        // SQLite's extended constraint codes are not all translated by Spring JDBC.
        Throwable cause = error.getMostSpecificCause();
        if (cause instanceof org.sqlite.SQLiteException sqlite) {
            int code = sqlite.getResultCode().code & 255;
            if (code == 19 || code == 5 || code == 6) {
                return ResponseEntity.status(409).body(Map.of("status", 409,
                    "message", "Conflicting listing, reference, or concurrent write; no changes committed"));
            }
        }
        return ResponseEntity.internalServerError().body(Map.of("status", 500, "message", "Catalogue storage failed"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "Invalid JSON or field value"));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> parameter() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "Invalid query parameter"));
    }
}
