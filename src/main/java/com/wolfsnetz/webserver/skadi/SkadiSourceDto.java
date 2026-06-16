package com.wolfsnetz.webserver.skadi;

public record SkadiSourceDto(
    String book,
    Integer page,
    String text,
    Double score
) {}
