package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/games/minecraft/mods")
public class ModCatalogueController {
    private final ModCatalogueService catalogue;
    public ModCatalogueController(ModCatalogueService catalogue) { this.catalogue = catalogue; }

    @GetMapping({"", "/"})
    public String browse(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String tag,
                         @RequestParam(required = false) String minecraftVersion, @RequestParam(required = false) Loader loader,
                         @RequestParam(required = false) Availability availability, @RequestParam(required = false) Maintenance maintenance,
                         @RequestParam(defaultValue = "false") boolean bothPrimary,
                         @RequestParam(defaultValue = "0") int offset, Model model) {
        model.addAttribute("page", catalogue.search(q, blank(tag), blank(minecraftVersion), loader, availability, maintenance,
            false, true, bothPrimary, offset, 12));
        filters(model, q, tag, minecraftVersion, loader, availability, maintenance, bothPrimary);
        model.addAttribute("tags", catalogue.tags(true));
        model.addAttribute("admin", false);
        return "mods/catalogue";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        Entry entry = catalogue.published(id);
        model.addAttribute("entry", entry);
        model.addAttribute("related", catalogue.projectLinks(entry.family(), true));
        model.addAttribute("incompatibilities", catalogue.incompatibilities(entry.family(), true));
        model.addAttribute("adminPreview", false);
        return "mods/detail";
    }

    void filters(Model model, String q, String tag, String version, Loader loader, Availability availability,
                 Maintenance maintenance, boolean bothPrimary) {
        model.addAttribute("q", q);
        model.addAttribute("selectedTag", tag);
        model.addAttribute("selectedVersion", version);
        model.addAttribute("selectedLoader", loader);
        model.addAttribute("selectedAvailability", availability);
        model.addAttribute("selectedMaintenance", maintenance);
        model.addAttribute("bothPrimary", bothPrimary);
        model.addAttribute("targets", catalogue.targets());
        model.addAttribute("versions", catalogue.targets().stream().map(Target::minecraftVersion).distinct().toList());
        model.addAttribute("loaders", Loader.values());
        model.addAttribute("availabilityOptions", Availability.values());
        model.addAttribute("maintenanceOptions", Maintenance.values());
    }
    static String blank(String value) { return value == null || value.isBlank() ? null : value; }
}
