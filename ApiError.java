package com.example.hotel.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    String traceId,
    Map<String, String> violations) {}
