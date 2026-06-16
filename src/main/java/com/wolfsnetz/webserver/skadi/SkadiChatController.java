package com.wolfsnetz.webserver.skadi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SkadiChatController
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SkadiChatController.class);
    private static final String UNAVAILABLE_MESSAGE = "The AI service is currently unavailable. Please try again later.";

    private final SkadiRagClient ragClient;

    public SkadiChatController(SkadiRagClient ragClient)
    {
        this.ragClient = ragClient;
    }

    @GetMapping({"/skadi", "/skadi/"})
    public String skadi()
    {
        return "skadi";
    }

    @GetMapping("/api/skadi/status")
    @ResponseBody
    public SkadiServiceStatus status()
    {
        return ragClient.status();
    }

    @PostMapping("/api/skadi/chat")
    @ResponseBody
    public ResponseEntity<?> chat(@RequestBody SkadiQuestionRequest request)
    {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                .body(new SkadiErrorResponse("Please enter a question."));
        }

        try {
            return ResponseEntity.ok(ragClient.chat(request.question().trim()));
        }
        catch (SkadiRagException e) {
            LOGGER.error("Skadi RAG chat request failed", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new SkadiErrorResponse(UNAVAILABLE_MESSAGE));
        }
    }
}
