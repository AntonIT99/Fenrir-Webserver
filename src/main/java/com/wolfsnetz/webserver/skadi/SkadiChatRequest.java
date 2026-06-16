package com.wolfsnetz.webserver.skadi;

import java.util.List;

public record SkadiChatRequest(
    String question,
    List<String> repositories,
    List<String> languages,
    String model
) {}
