package com.example.hotel.gateway.tracing;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter implements WebFilter {
  public static final String HEADER = "X-Request-Id";
  public static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
    String id =
        supplied != null && SAFE_ID.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    exchange.getAttributes().put(ATTRIBUTE, id);
    ServerWebExchange traced =
        exchange
            .mutate()
            .request(request -> request.headers(headers -> headers.set(HEADER, id)))
            .build();
    traced.getResponse().getHeaders().set(HEADER, id);
    traced
        .getResponse()
        .beforeCommit(
            () -> {
              traced.getResponse().getHeaders().set(HEADER, id);
              return Mono.empty();
            });
    long start = System.nanoTime();
    return chain
        .filter(traced)
        .doFinally(
            signal ->
                log.atInfo()
                    .addKeyValue("traceId", id)
                    .addKeyValue("method", traced.getRequest().getMethod().name())
                    .addKeyValue("path", traced.getRequest().getPath().value())
                    .addKeyValue(
                        "status",
                        traced.getResponse().getStatusCode() == null
                            ? 200
                            : traced.getResponse().getStatusCode().value())
                    .addKeyValue(
                        "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .log("HTTP request completed"))
        .contextWrite(context -> context.put("traceId", id));
  }

  public static String traceId(ServerWebExchange exchange) {
    return exchange.getAttributeOrDefault(ATTRIBUTE, "");
  }
}
