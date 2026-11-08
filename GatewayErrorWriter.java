package com.example.hotel.gateway.error;

import com.example.hotel.gateway.tracing.RequestTraceFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayErrorWriter {
  private final ObjectMapper objectMapper;

  public GatewayErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Mono<Void> write(
      ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.empty();
    }
    GatewayError body =
        new GatewayError(
            Instant.now(),
            status.value(),
            code,
            message,
            exchange.getRequest().getPath().value(),
            RequestTraceFilter.traceId(exchange),
            Map.of());
    final byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(body);
    } catch (JsonProcessingException exception) {
      return Mono.error(exception);
    }
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return exchange
        .getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
  }
}
