package com.example.hotel.gateway.security;

import com.example.hotel.gateway.error.GatewayErrorWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurity {
  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, GatewayErrorWriter errors) {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
    authentication.setJwtGrantedAuthoritiesConverter(authorities);

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .authorizeExchange(
            authorize ->
                authorize
                    .pathMatchers(
                        HttpMethod.POST,
                        "/user/register",
                        "/user/auth",
                        "/api/bookings/user/register",
                        "/api/bookings/user/auth")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .pathMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .anyExchange()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (exchange, exception) -> {
                          exchange
                              .getResponse()
                              .getHeaders()
                              .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                          return errors.write(
                              exchange,
                              HttpStatus.UNAUTHORIZED,
                              "UNAUTHORIZED",
                              "A valid bearer token is required");
                        })
                    .accessDeniedHandler(
                        (exchange, exception) ->
                            errors.write(
                                exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied")))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(authentication)))
                    .authenticationEntryPoint(
                        (exchange, exception) -> {
                          exchange
                              .getResponse()
                              .getHeaders()
                              .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                          return errors.write(
                              exchange,
                              HttpStatus.UNAUTHORIZED,
                              "UNAUTHORIZED",
                              "A valid bearer token is required");
                        })
                    .accessDeniedHandler(
                        (exchange, exception) ->
                            errors.write(
                                exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied")))
        .build();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(
      @Value("${security.jwt.secret}") String secret,
      @Value("${security.jwt.issuer:hotel-booking}") String issuer,
      @Value("${security.jwt.audience:hotel-api}") String audience) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) {
      throw new IllegalArgumentException(
          "security.jwt.secret must contain at least 32 UTF-8 bytes");
    }
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> claims =
        jwt -> {
          Object roles = jwt.getClaims().get("roles");
          boolean valid =
              jwt.getExpiresAt() != null
                  && jwt.getSubject() != null
                  && !jwt.getSubject().isBlank()
                  && jwt.getAudience() != null
                  && jwt.getAudience().contains(audience)
                  && roles instanceof Collection<?> values
                  && !values.isEmpty()
                  && values.stream().allMatch(role -> List.of("USER", "ADMIN").contains(role));
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error(
                      "invalid_token", "Required token claims are missing or invalid", null));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claims));
    return decoder;
  }
}
