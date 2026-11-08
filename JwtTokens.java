package com.example.hotel.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Distinct trust domains prevent user JWTs from calling internal allocation endpoints. */
public final class JwtTokens {
  private final JwtEncoder userEncoder;
  private final JwtEncoder internalEncoder;
  private final JwtDecoder userDecoder;
  private final JwtDecoder internalDecoder;

  public JwtTokens(String userSecret, String internalSecret) {
    if (userSecret.equals(internalSecret))
      throw new IllegalArgumentException("User and internal JWT keys must differ");
    userEncoder = encoder(userSecret);
    internalEncoder = encoder(internalSecret);
    userDecoder = decoder(userSecret, "hotel-booking", "hotel-api", false);
    internalDecoder = decoder(internalSecret, "hotel-booking-internal", "hotel-internal", true);
  }

  public String issueUser(long id, String username, String role) {
    if (!List.of("USER", "ADMIN").contains(role))
      throw new IllegalArgumentException("Unsupported user role");
    return issue(
        userEncoder, "hotel-booking", "hotel-api", Long.toString(id), username, role, 3600);
  }

  public String issueInternal() {
    return issue(
        internalEncoder,
        "hotel-booking-internal",
        "hotel-internal",
        "booking-service",
        "booking-service",
        "INTERNAL",
        120);
  }

  public JwtDecoder decoder() {
    return token -> {
      try {
        return userDecoder.decode(token);
      } catch (JwtException ignored) {
        return internalDecoder.decode(token);
      }
    };
  }

  private static String issue(
      JwtEncoder encoder,
      String issuer,
      String audience,
      String subject,
      String username,
      String role,
      int seconds) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(seconds))
            .claim("username", username)
            .claim("roles", List.of(role))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private static SecretKeySpec key(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalArgumentException("JWT key requires at least 32 UTF-8 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  private static JwtEncoder encoder(String secret) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
  }

  private static JwtDecoder decoder(
      String secret, String issuer, String audience, boolean internal) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> shape =
        jwt -> {
          Object rawRoles = jwt.getClaims().get("roles");
          boolean valid =
              jwt.getExpiresAt() != null
                  && jwt.getSubject() != null
                  && !jwt.getSubject().isBlank()
                  && jwt.getAudience() != null
                  && jwt.getAudience().contains(audience)
                  && rawRoles instanceof List<?> roles
                  && !roles.isEmpty()
                  && roles.stream()
                      .allMatch(
                          r ->
                              internal
                                  ? "INTERNAL".equals(r)
                                  : List.of("USER", "ADMIN").contains(r));
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "Invalid JWT claims", null));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), shape));
    return decoder;
  }
}
