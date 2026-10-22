package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.util.List;
import java.util.UUID;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/mods")
@PreAuthorize("hasRole('ADMIN')")
public class ModAdminController {
    private final ModCatalogueService catalogue;
    private final ModCatalogueController publicController;
    public ModAdminController(ModCatalogueService catalogue, ModCatalogueController publicController) {
        this.catalogue = catalogue;
        this.publicController = publicController;
    }

    @GetMapping({"", "/"})
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String tag,
                       @RequestParam(required = false) String minecraftVersion, @RequestParam(required = false) Loader loader,
                       @RequestParam(required = false) Availability availability, @RequestParam(required = false) Maintenance maintenance,
                       @RequestParam(defaultValue = "false") boolean bothPrimary,
                       @RequestParam(defaultValue = "0") int offset, Model model) {
        model.addAttribute("page", catalogue.search(q, ModCatalogueController.blank(tag), ModCatalogueController.blank(minecraftVersion), loader,
            availability, maintenance, true, false, bothPrimary, offset, 12));
        publicController.filters(model, q, tag, minecraftVersion, loader, availability, maintenance, bothPrimary);
        model.addAttribute("tags", catalogue.tags(false));
        model.addAttribute("admin", true);
        return "mods/catalogue";
    }

    @GetMapping("/new")
    public String create(Model model) {
        Family family = new Family("", "", "", List.of(), List.of(), false, false,
            List.of(new Project("", "", "", List.of(), List.of(), List.of())), List.of(), List.of());
        editor(model, new Entry(0, "", family));
        return "mods/editor";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        editor(model, catalogue.get(id));
        return "mods/editor";
    }

    private void editor(Model model, Entry entry) {
        model.addAttribute("entry", entry);
        model.addAttribute("familyJson", catalogue.familyJson(entry.family()));
    }

    @GetMapping("/{id}/preview")
    public String preview(@PathVariable String id, Model model) {
        Entry entry = catalogue.get(id);
        model.addAttribute("entry", entry);
        model.addAttribute("related", catalogue.projectLinks(entry.family(), false));
        model.addAttribute("incompatibilities", catalogue.incompatibilities(entry.family(), false));
        model.addAttribute("adminPreview", true);
        return "mods/detail";
    }

    @GetMapping("/import")
    public String imports() { return "mods/import"; }

    @GetMapping("/{id}/history")
    public String history(@PathVariable String id, @RequestParam(defaultValue = "0") int offset, Model model) {
        model.addAttribute("entry", catalogue.get(id));
        model.addAttribute("history", catalogue.history(id, offset, 25));
        model.addAttribute("offset", offset);
        return "mods/history";
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable String id, @RequestParam long revision, @RequestParam long expectedRevision,
                          @RequestParam String reason, Principal principal, RedirectAttributes redirect) {
        try {
            catalogue.apply(new Batch(UUID.randomUUID().toString(), reason,
                List.of(new Change(expectedRevision, catalogue.revision(id, revision).family()))), false, principal.getName());
            redirect.addFlashAttribute("notice", "Revision " + revision + " restored as a new revision.");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/admin/mods/" + id + "/history";
    }
}
