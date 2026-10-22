package com.wolfsnetz.webserver.minecraft;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MinecraftController
{
    private final MinecraftStatusService service;

    public MinecraftController(MinecraftStatusService service)
    {
        this.service = service;
    }

    @GetMapping({"/minecraft", "/minecraft/", "/minecraft/index.html"})
    public String minecraft() {
        return "redirect:/games/minecraft/index.html";
    }

    @GetMapping("/api/minecraft/status")
    @ResponseBody
    public MinecraftStatus status()
    {
        return service.getStatus();
    }
}
