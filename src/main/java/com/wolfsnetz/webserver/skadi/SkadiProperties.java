package com.wolfsnetz.webserver.skadi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@ConfigurationProperties(prefix = "skadi.rag-service")
public record SkadiProperties(
    List<Worker> workers,
    String defaultModel,
    List<String> defaultRepositories,
    List<String> defaultLanguages,
    int connectTimeoutMs,
    int requestTimeoutMs,
    int healthTimeoutMs
) {
    public SkadiProperties
    {
        workers = workers == null || workers.isEmpty()
            ? List.of(new Worker("local", "http://localhost:8000", 1))
            : List.copyOf(workers);
        defaultModel = defaultModel == null || defaultModel.isBlank() ? "qwen3:14b" : defaultModel;
        defaultRepositories = defaultRepositories == null || defaultRepositories.isEmpty()
            ? List.of("default", "sensitive")
            : List.copyOf(defaultRepositories);
        defaultLanguages = defaultLanguages == null || defaultLanguages.isEmpty()
            ? List.of("de", "en", "fr")
            : List.copyOf(defaultLanguages);
        connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 1200;
        requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : 30000;
        healthTimeoutMs = healthTimeoutMs > 0 ? healthTimeoutMs : 1200;
    }

    public List<Worker> orderedWorkers()
    {
        return workers.stream()
            .sorted(Comparator.comparingInt(Worker::priority))
            .toList();
    }

    public Duration connectTimeout()
    {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration requestTimeout()
    {
        return Duration.ofMillis(requestTimeoutMs);
    }

    public Duration healthTimeout()
    {
        return Duration.ofMillis(healthTimeoutMs);
    }

    public record Worker(String name, String baseUrl, int priority)
    {
        public Worker
        {
            name = name == null || name.isBlank() ? "worker-" + priority : name;
            baseUrl = baseUrl == null ? "" : baseUrl;
            priority = priority > 0 ? priority : 100;
        }
    }
}
