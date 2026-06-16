package com.wolfsnetz.webserver.skadi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkadiRagClientTest
{
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers()
    {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    void chatFallsBackToSecondWorkerWhenPrimaryReturnsError() throws Exception
    {
        HttpServer primary = startServer();
        primary.createContext("/chat", exchange -> send(exchange, 500, "{\"error\":\"down\"}"));

        AtomicReference<String> fallbackRequestBody = new AtomicReference<>();
        HttpServer fallback = startServer();
        fallback.createContext("/chat", exchange -> {
            fallbackRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, """
                {
                  "answer": "Antwort",
                  "sources": [
                    {
                      "book": "example",
                      "page": 42,
                      "text": "excerpt",
                      "score": 0.82
                    }
                  ]
                }
                """);
        });

        SkadiRagClient client = new SkadiRagClient(properties(primary, fallback));

        SkadiChatResponse response = client.chat("Was sagt Nietzsche ueber Moral?");

        assertEquals("Antwort", response.answer());
        assertEquals("fallback", response.workerName());
        assertEquals(1, response.sources().size());
        assertEquals("example", response.sources().get(0).book());
        assertEquals(42, response.sources().get(0).page());
        assertEquals(0.82, response.sources().get(0).score());
        assertTrue(fallbackRequestBody.get().contains("\"repositories\":[\"default\",\"sensitive\"]"));
        assertTrue(fallbackRequestBody.get().contains("\"languages\":[\"de\",\"en\",\"fr\"]"));
        assertTrue(fallbackRequestBody.get().contains("\"model\":\"qwen3:14b\""));
    }

    @Test
    void statusReportsFallbackWhenOnlySecondWorkerIsHealthy() throws Exception
    {
        HttpServer primary = startServer();
        primary.createContext("/health", exchange -> send(exchange, 500, "{\"status\":\"down\"}"));

        HttpServer fallback = startServer();
        fallback.createContext("/health", exchange -> send(exchange, 200, "{\"status\":\"ok\"}"));

        SkadiRagClient client = new SkadiRagClient(properties(primary, fallback));

        SkadiServiceStatus status = client.status();

        assertTrue(status.online());
        assertEquals("fallback", status.activeWorkerName());
        assertTrue(status.fallbackAvailable());
    }

    private SkadiProperties properties(HttpServer primary, HttpServer fallback)
    {
        return new SkadiProperties(
            List.of(
                new SkadiProperties.Worker("primary", baseUrl(primary), 1),
                new SkadiProperties.Worker("fallback", baseUrl(fallback), 2)
            ),
            "qwen3:14b",
            List.of("default", "sensitive"),
            List.of("de", "en", "fr"),
            100,
            2000,
            100
        );
    }

    private HttpServer startServer() throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        servers.add(server);
        return server;
    }

    private String baseUrl(HttpServer server)
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
