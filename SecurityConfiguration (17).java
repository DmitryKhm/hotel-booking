package com.example.hotel.hotel.config;

import com.example.hotel.common.SecuritySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                SecuritySupport.authenticationConverter()))
                    .authenticationEntryPoint(SecuritySupport.entryPoint(mapper))
                    .accessDeniedHandler(SecuritySupport.deniedHandler(mapper)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(SecuritySupport.entryPoint(mapper))
                    .accessDeniedHandler(SecuritySupport.deniedHandler(mapper)))
        .build();
  }
}
