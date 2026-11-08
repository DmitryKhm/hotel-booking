package com.example.hotel.gateway.routing;

import java.util.List;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** These operations are service-to-service only and must never be exposed by the gateway. */
public final class InternalEndpoints {
  private static final List<PathPattern> PATTERNS =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/rooms/{id}/confirm-availability/**"),
          PathPatternParser.defaultInstance.parse("/api/rooms/{id}/release/**"));

  private InternalEndpoints() {}

  public static boolean matches(ServerWebExchange exchange) {
    return PATTERNS.stream()
        .anyMatch(
            pattern -> pattern.matches(exchange.getRequest().getPath().pathWithinApplication()));
  }
}
