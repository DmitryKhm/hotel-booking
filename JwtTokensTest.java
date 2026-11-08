package com.example.hotel.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JwtTokensTest {
  private final JwtTokens tokens =
      new JwtTokens(
          "test-user-key-long-enough-at-least-32-bytes",
          "test-internal-key-long-enough-at-least-32-bytes");

  @Test
  void userTokenHasOneHourLifetimeAndUserIdentity() {
    var jwt = tokens.decoder().decode(tokens.issueUser(42, "alice", "USER"));
    assertThat(jwt.getSubject()).isEqualTo("42");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond())
        .isEqualTo(3600);
  }

  @Test
  void internalTokenUsesSeparateIssuerAndRole() {
    var jwt = tokens.decoder().decode(tokens.issueInternal());
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("hotel-booking-internal");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("INTERNAL");
  }

  @Test
  void userCannotReceiveInternalRole() {
    assertThatIllegalArgumentException().isThrownBy(() -> tokens.issueUser(1, "alice", "INTERNAL"));
  }

  @Test
  void unknownKeysAndShortOrSharedKeysAreRejected() {
    var other =
        new JwtTokens(
            "other-user-key-long-enough-at-least-32-bytes",
            "other-internal-key-long-enough-at-least-32-bytes");
    assertThatThrownBy(() -> tokens.decoder().decode(other.issueUser(1, "mallory", "ADMIN")))
        .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    assertThatIllegalArgumentException().isThrownBy(() -> new JwtTokens("short", "different"));
    assertThatIllegalArgumentException().isThrownBy(() -> new JwtTokens("same", "same"));
  }
}
