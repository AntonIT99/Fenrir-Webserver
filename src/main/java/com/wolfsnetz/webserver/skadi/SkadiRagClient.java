package com.wolfsnetz.webserver.skadi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class SkadiRagClient
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SkadiRagClient.class);

    private final SkadiProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkadiRagClient(SkadiProperties properties)
    {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .build();
    }

    public SkadiChatResponse chat(String question)
    {
        // TODO: Replace prototype defaults with server-side repository access policy once authentication is integrated.
        // Public users should eventually only get ["default"], while research/admin users may get ["default", "sensitive"].
        SkadiChatRequest chatRequest = new SkadiChatRequest(
            question,
            properties.defaultRepositories(),
            properties.defaultLanguages(),
            properties.defaultModel()
        );

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(chatRequest);
        }
        catch (RuntimeException e) {
            throw new SkadiRagException("Could not serialize Skadi chat request", e);
        }

        Exception lastFailure = null;
        for (SkadiProperties.Worker worker : properties.orderedWorkers()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint(worker, "/chat"))
                    .timeout(properties.requestTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException(
                        "HTTP " + response.statusCode() + " from " + worker.name() + ": " + response.body()
                    );
                }

                SkadiChatResponse chatResponse = parseChatResponse(response.body(), worker.name());
                LOGGER.info("Skadi RAG request served by worker {}", worker.name());
                return chatResponse;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkadiRagException("Skadi RAG request was interrupted", e);
            }
            catch (Exception e) {
                lastFailure = e;
                LOGGER.warn("Skadi RAG worker {} failed: {}", worker.name(), e.toString());
            }
        }

        throw new SkadiRagException("All Skadi RAG workers failed", lastFailure);
    }

    public SkadiServiceStatus status()
    {
        List<SkadiProperties.Worker> workers = properties.orderedWorkers();
        SkadiProperties.Worker activeWorker = null;
        boolean fallbackAvailable = false;

        for (int i = 0; i < workers.size(); i++) {
            SkadiProperties.Worker worker = workers.get(i);
            boolean healthy = isHealthy(worker);

            if (healthy && activeWorker == null) {
                activeWorker = worker;
            }

            if (healthy && i > 0) {
                fallbackAvailable = true;
            }
        }

        return new SkadiServiceStatus(
            activeWorker != null,
            activeWorker == null ? null : activeWorker.name(),
            fallbackAvailable
        );
    }

    private boolean isHealthy(SkadiProperties.Worker worker)
    {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(worker, "/health"))
                .timeout(properties.healthTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            return response.statusCode() >= 200 && response.statusCode() < 300;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (Exception e) {
            LOGGER.debug("Skadi health check failed for worker {}: {}", worker.name(), e.toString());
            return false;
        }
    }

    private SkadiChatResponse parseChatResponse(String body, String workerName) throws IOException
    {
        JsonNode root = objectMapper.readTree(body);
        String answer = root.path("answer").asString("");
        List<SkadiSourceDto> sources = new ArrayList<>();
        JsonNode sourceNodes = root.path("sources");

        if (sourceNodes.isArray()) {
            for (JsonNode source : sourceNodes) {
                sources.add(new SkadiSourceDto(
                    stringOrNull(source, "book"),
                    intOrNull(source, "page"),
                    stringOrNull(source, "text"),
                    doubleOrNull(source, "score")
                ));
            }
        }

        return new SkadiChatResponse(answer, sources, workerName);
    }

    private URI endpoint(SkadiProperties.Worker worker, String path)
    {
        String baseUrl = worker.baseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return URI.create(baseUrl + path);
    }

    private String stringOrNull(JsonNode node, String fieldName)
    {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private Integer intOrNull(JsonNode node, String fieldName)
    {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private Double doubleOrNull(JsonNode node, String fieldName)
    {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }
}
