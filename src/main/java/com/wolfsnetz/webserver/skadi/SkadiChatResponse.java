package com.wolfsnetz.webserver.skadi;

import java.util.List;

public record SkadiChatResponse(
    String answer,
    List<SkadiSourceDto> sources,
    String workerName
) {}
