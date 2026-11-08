package com.example.hotel.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

  public static String traceId(HttpServletRequest request) {
    Object value = request.getAttribute("traceId");
    return value == null ? "unknown" : value.toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String trace = request.getHeader("X-Request-Id");
    if (trace == null || !trace.matches("[a-zA-Z0-9._-]{1,64}"))
      trace = UUID.randomUUID().toString();
    request.setAttribute("traceId", trace);
    response.setHeader("X-Request-Id", trace);
    MDC.put("traceId", trace);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      log.info(
          "event=http_request method={} path={} status={} durationMs={} traceId={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          (System.nanoTime() - start) / 1_000_000,
          trace);
      MDC.remove("traceId");
    }
  }
}
