package com.example.hotel.gateway.error;

import java.time.Instant;
import java.util.Map;

public record GatewayError(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    String traceId,
    Map<String, String> violations) {}
