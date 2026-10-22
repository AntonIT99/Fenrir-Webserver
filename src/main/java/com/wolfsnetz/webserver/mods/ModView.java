package com.wolfsnetz.webserver.mods;

import static com.wolfsnetz.webserver.mods.ModModels.*;
import java.util.List;
import org.springframework.stereotype.Component;

@Component("modView")
public class ModView {
    public List<Target> primaryTargets() {
        return List.of(new Target("1.20.1", Loader.FORGE), new Target("1.21.1", Loader.NEOFORGE),
            new Target("1.7.10", Loader.FORGE), new Target("1.12.2", Loader.FORGE));
    }
    public Support support(Project project, Target target) {
        return project.support().stream().filter(s -> s.target().equals(target)).findFirst().orElse(null);
    }
    public String label(Object value) {
        if (value == null) { return "Not researched"; }
        switch (value.toString()) {
            case "NEOFORGE": return "NeoForge";
            case "CURSEFORGE": return "CurseForge";
            case "MODRINTH": return "Modrinth";
            case "scifi": return "Sci-fi";
            default: break;
        }
        String label = value.toString().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
    public String target(Target target) { return target.minecraftVersion() + " · " + label(target.loader()); }
    public String projectName(Family family, String id) {
        return family.projects().stream().filter(p -> p.id().equals(id)).map(Project::name).findFirst().orElse(id);
    }
}
