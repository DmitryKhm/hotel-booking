package com.example.hotel.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, TraceFilter.class})
public class CommonConfiguration {
  @Bean
  JwtTokens jwtTokens(
      @Value(
              "${security.jwt.secret:${JWT_SECRET:development-only-user-signing-key-change-me-0123456789}}")
          String userSecret,
      @Value(
              "${security.jwt.internal-secret:${INTERNAL_JWT_SECRET:development-only-internal-signing-key-change-me-9876543210}}")
          String internalSecret) {
    return new JwtTokens(userSecret, internalSecret);
  }

  @Bean
  JwtDecoder jwtDecoder(JwtTokens tokens) {
    return tokens.decoder();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }
}
