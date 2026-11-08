package com.example.hotel.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
  private final GatewayErrorWriter errors;

  public GatewayExceptionHandler(GatewayErrorWriter errors) {
    this.errors = errors;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(exception);
    }
    HttpStatusCode status =
        exception instanceof ResponseStatusException responseStatus
            ? responseStatus.getStatusCode()
            : HttpStatus.INTERNAL_SERVER_ERROR;
    String code;
    String message;
    if (status.value() == 404) {
      code = "NOT_FOUND";
      message = "Resource not found";
    } else if (status.value() == 503) {
      code = "SERVICE_UNAVAILABLE";
      message = "The requested service is temporarily unavailable";
    } else if (status.value() == 504) {
      code = "GATEWAY_TIMEOUT";
      message = "The requested service did not respond in time";
    } else if (status.is4xxClientError()) {
      code = "BAD_REQUEST";
      message = "Request could not be processed";
    } else {
      code = "INTERNAL_ERROR";
      message = "An unexpected gateway error occurred";
      log.error("Gateway request failed", exception);
    }
    return errors.write(exchange, status, code, message);
  }
}
