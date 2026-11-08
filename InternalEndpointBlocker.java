package com.example.hotel.gateway.security;

import com.example.hotel.gateway.error.GatewayErrorWriter;
import com.example.hotel.gateway.routing.InternalEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalEndpointBlocker implements WebFilter {
  private final GatewayErrorWriter errors;

  public InternalEndpointBlocker(GatewayErrorWriter errors) {
    this.errors = errors;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (InternalEndpoints.matches(exchange)) {
      return errors.write(
          exchange,
          HttpStatus.FORBIDDEN,
          "FORBIDDEN",
          "Internal service endpoints are not exposed through the gateway");
    }
    return chain.filter(exchange);
  }
}
