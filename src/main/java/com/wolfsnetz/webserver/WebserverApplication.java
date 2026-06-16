package com.wolfsnetz.webserver;

import com.wolfsnetz.webserver.minecraft.MinecraftProperties;
import com.wolfsnetz.webserver.skadi.SkadiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({MinecraftProperties.class, SkadiProperties.class})
@SpringBootApplication
public class WebserverApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WebserverApplication.class, args);
    }
}
