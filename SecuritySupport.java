package com.example.hotel.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class SecuritySupport {
  private SecuritySupport() {}

  public static JwtAuthenticationConverter authenticationConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return converter;
  }

  public static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
    return (request, response, error) -> {
      response.setHeader("WWW-Authenticate", "Bearer");
      write(mapper, request, response, 401, "UNAUTHORIZED", "Authentication is required");
    };
  }

  public static AccessDeniedHandler deniedHandler(ObjectMapper mapper) {
    return (request, response, error) ->
        write(mapper, request, response, 403, "FORBIDDEN", "Access is denied");
  }

  private static void write(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    mapper.writeValue(
        response.getOutputStream(),
        new ApiError(
            Instant.now(),
            status,
            code,
            message,
            request.getRequestURI(),
            TraceFilter.traceId(request),
            Map.of()));
  }
}
