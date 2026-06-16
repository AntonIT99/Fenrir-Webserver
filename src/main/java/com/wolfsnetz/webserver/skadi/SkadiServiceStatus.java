package com.wolfsnetz.webserver.skadi;

public record SkadiServiceStatus(
    boolean online,
    String activeWorkerName,
    boolean fallbackAvailable
) {}
