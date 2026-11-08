package com.example.hotel.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class GatewayIntegrationTest {
  private static final String SECRET = "development-only-user-signing-key-change-me-0123456789";
  private static final ConcurrentLinkedQueue<String> RECEIVED = new ConcurrentLinkedQueue<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DisposableServer BACKEND =
      HttpServer.create()
          .host("127.0.0.1")
          .port(0)
          .handle(
              (request, response) ->
                  request
                      .receive()
                      .aggregate()
                      .asString()
                      .defaultIfEmpty("")
                      .flatMap(
                          body -> {
                            RECEIVED.add(request.uri());
                            try {
                              String json =
                                  JSON.writeValueAsString(
                                      Map.of(
                                          "uri", request.uri(),
                                          "authorization",
                                              valueOrEmpty(
                                                  request.requestHeaders().get("Authorization")),
                                          "traceId",
                                              valueOrEmpty(
                                                  request.requestHeaders().get("X-Request-Id")),
                                          "body", body));
                              return response
                                  .header("Content-Type", "application/json")
                                  .sendString(Mono.just(json))
                                  .then();
                            } catch (JsonProcessingException exception) {
                              return Mono.error(exception);
                            }
                          }))
          .bindNow();

  @Autowired private WebTestClient client;

  @LocalServerPort private int gatewayPort;

  @DynamicPropertySource
  static void backendUris(DynamicPropertyRegistry registry) {
    registry.add("gateway.services.booking-uri", () -> "http://127.0.0.1:" + BACKEND.port());
    registry.add("gateway.services.hotel-uri", () -> "http://127.0.0.1:" + BACKEND.port());
    registry.add("security.jwt.secret", () -> SECRET);
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.disposeNow();
  }

  @Test
  void protectedEndpointRequiresBearerTokenAndReturnsUnifiedError() {
    client
        .get()
        .uri("/api/hotels")
        .header("X-Request-Id", "request-401")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals("WWW-Authenticate", "Bearer")
        .expectHeader()
        .valueEquals("X-Request-Id", "request-401")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED")
        .jsonPath("$.message")
        .isNotEmpty()
        .jsonPath("$.timestamp")
        .isNotEmpty()
        .jsonPath("$.path")
        .isEqualTo("/api/hotels")
        .jsonPath("$.traceId")
        .isEqualTo("request-401")
        .jsonPath("$.violations")
        .isMap();
  }

  @ParameterizedTest
  @CsvSource({
    "/user/register,/user/register",
    "/user/auth,/user/auth",
    "/api/bookings/user/register,/user/register",
    "/api/bookings/user/auth,/user/auth"
  })
  void authenticationEndpointsArePublicAndAliasesAreRewritten(String requested, String forwarded) {
    client
        .post()
        .uri(requested)
        .header("X-Request-Id", "registration-trace")
        .header(HttpHeaders.CONTENT_TYPE, "application/json")
        .bodyValue("{\"username\":\"alice\"}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.uri")
        .isEqualTo(forwarded)
        .jsonPath("$.body")
        .isEqualTo("{\"username\":\"alice\"}")
        .jsonPath("$.traceId")
        .isEqualTo("registration-trace");
  }

  @ParameterizedTest
  @CsvSource({
    "/api/bookings,/bookings",
    "/api/bookings/42,/booking/42",
    "/api/bookings/booking/42,/booking/42",
    "/api/bookings/bookings,/bookings",
    "/api/bookings/user/profile,/user/profile",
    "/booking/42,/booking/42",
    "/bookings,/bookings",
    "/user/profile,/user/profile",
    "/api/hotels/7,/api/hotels/7",
    "/api/rooms/3,/api/rooms/3"
  })
  void routesPreserveBearerTokenQueryAndTrace(String requested, String forwarded) {
    String bearer = "Bearer " + token("USER", "valid");
    client
        .get()
        .uri(requested + "?page=2")
        .header(HttpHeaders.AUTHORIZATION, bearer)
        .header("X-Request-Id", "route-test-42")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Request-Id", "route-test-42")
        .expectBody()
        .jsonPath("$.uri")
        .isEqualTo(forwarded + "?page=2")
        .jsonPath("$.authorization")
        .isEqualTo(bearer)
        .jsonPath("$.traceId")
        .isEqualTo("route-test-42");
  }

  @Test
  void collectionPostAliasPreservesBodyAndUsesSingularBackendPath() {
    String bearer = "Bearer " + token("USER", "valid");
    String body = "{\"requestId\":\"3ce67896-1f80-460e-a60b-2739f4d23f64\",\"autoSelect\":true}";

    client
        .post()
        .uri("/api/bookings")
        .header(HttpHeaders.AUTHORIZATION, bearer)
        .header(HttpHeaders.CONTENT_TYPE, "application/json")
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.uri")
        .isEqualTo("/booking")
        .jsonPath("$.body")
        .isEqualTo(body)
        .jsonPath("$.authorization")
        .isEqualTo(bearer);
  }

  @Test
  void numericItemDeleteAliasUsesBookingBackendPath() {
    client
        .delete()
        .uri("/api/bookings/42")
        .headers(headers -> headers.setBearerAuth(token("USER", "valid")))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.uri")
        .isEqualTo("/booking/42");
  }

  @Test
  void collectionAliasesRequireAuthenticationAndDoNotCaptureNonNumericItems() {
    client.get().uri("/api/bookings").exchange().expectStatus().isUnauthorized();
    client.post().uri("/api/bookings").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/api/bookings/not-a-booking-id")
        .headers(headers -> headers.setBearerAuth(token("USER", "valid")))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "malformed",
        "wrong-signature",
        "wrong-issuer",
        "wrong-audience",
        "expired",
        "no-expiration",
        "no-subject",
        "invalid-roles",
        "empty-roles",
        "internal-role"
      })
  void rejectsInvalidTokens(String variant) {
    String value = variant.equals("malformed") ? "this-is-not-a-jwt" : token("USER", variant);
    client
        .get()
        .uri("/bookings")
        .headers(headers -> headers.setBearerAuth(value))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/rooms/3/confirm-availability",
        "/api/rooms/3/release",
        "/api/rooms/3/confirm-availability/",
        "/api/rooms/3/release/",
        "/api/rooms/3/confirm%2Davailability",
        "/api/rooms/3/release/anything"
      })
  void internalEndpointsNeverReachBackendEvenForAdmin(String path) {
    int requestsBefore = RECEIVED.size();
    URI endpoint = URI.create("http://127.0.0.1:" + gatewayPort + path);
    client
        .post()
        .uri(endpoint)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    client
        .post()
        .uri(endpoint)
        .headers(headers -> headers.setBearerAuth(token("ADMIN", "valid")))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    assertThat(RECEIVED.size()).isEqualTo(requestsBefore);
  }

  @Test
  void healthIsPublicWhileOtherActuatorEndpointsRequireAdmin() {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    client.get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/actuator/info")
        .headers(headers -> headers.setBearerAuth(token("USER", "valid")))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(403)
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    client
        .get()
        .uri("/actuator/info")
        .headers(headers -> headers.setBearerAuth(token("ADMIN", "valid")))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void unknownRoutesReturnUnified404AndDoNotExposeDiscoveryRoutes() {
    int requestsBefore = RECEIVED.size();
    client
        .get()
        .uri("/hotel-service/api/hotels")
        .headers(headers -> headers.setBearerAuth(token("USER", "valid")))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404)
        .jsonPath("$.code")
        .isEqualTo("NOT_FOUND")
        .jsonPath("$.traceId")
        .isNotEmpty();
    assertThat(RECEIVED.size()).isEqualTo(requestsBefore);
  }

  @Test
  void missingOrUnsafeTraceIdsAreReplacedAndForwarded() {
    for (String supplied : List.of("", "unsafe request id", "x".repeat(65))) {
      var request =
          client
              .get()
              .uri("/bookings")
              .headers(headers -> headers.setBearerAuth(token("USER", "valid")));
      if (!supplied.isEmpty()) {
        request.header("X-Request-Id", supplied);
      }
      var result = request.exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
      String generated = result.getResponseHeaders().getFirst("X-Request-Id");
      assertThat(generated).matches("[0-9a-f-]{36}");
      assertThat(result.getResponseBody()).containsEntry("traceId", generated);
    }
  }

  private static String token(String role, String variant) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .subject(variant.equals("no-subject") ? null : "user-1")
            .issuer(variant.equals("wrong-issuer") ? "other-issuer" : "hotel-booking")
            .audience(variant.equals("wrong-audience") ? "internal-api" : "hotel-api")
            .issueTime(Date.from(now.minusSeconds(10)))
            .claim(
                "roles",
                switch (variant) {
                  case "invalid-roles" -> "ADMIN";
                  case "empty-roles" -> List.of();
                  case "internal-role" -> List.of("INTERNAL");
                  default -> List.of(role);
                });
    if (!variant.equals("no-expiration")) {
      claims.expirationTime(
          Date.from(variant.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(3600)));
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    try {
      String key =
          variant.equals("wrong-signature") ? "another-test-secret-at-least-32-bytes-long" : SECRET;
      jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }
}
