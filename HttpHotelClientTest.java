package com.example.hotel.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.example.hotel.booking.client.HotelClient;
import com.example.hotel.booking.client.HttpHotelClient;
import com.example.hotel.booking.client.RemoteHotelException;
import com.example.hotel.common.JwtTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpHotelClientTest {
  @Test
  void retriesTransientErrorsWithIdenticalOperationAndServiceToken() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    for (int attempt = 0; attempt < 2; attempt++) {
      server
          .expect(requestTo("http://hotel.test/api/rooms/7/confirm-availability"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              request -> {
                String authorization = request.getHeaders().getFirst("Authorization");
                assertThat(authorization).startsWith("Bearer ");
                assertThat(
                        tokens()
                            .decoder()
                            .decode(authorization.substring(7))
                            .getClaimAsStringList("roles"))
                    .containsExactly("INTERNAL");
              })
          .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    }
    server
        .expect(requestTo("http://hotel.test/api/rooms/7/confirm-availability"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    HttpHotelClient client = client(builder, "http://hotel.test", 3);

    client.confirm(7, slot());

    server.verify();
  }

  @Test
  void conflictIsNeverRetried() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://hotel.test/api/rooms/7/confirm-availability"))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> client(builder, "http://hotel.test", 3).confirm(7, slot()))
        .isInstanceOfSatisfying(
            RemoteHotelException.class,
            error -> {
              assertThat(error.status()).isEqualTo(409);
              assertThat(error.retryable()).isFalse();
            });
    server.verify();
  }

  @Test
  void breakerStopsAdmissionsButStillAllowsCompensation() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    for (int call = 0; call < 5; call++) {
      server
          .expect(requestTo("http://hotel.test/api/rooms/7/confirm-availability"))
          .andRespond(withServerError());
    }
    server.expect(requestTo("http://hotel.test/api/rooms/7/release")).andRespond(withNoContent());
    HttpHotelClient client = client(builder, "http://hotel.test", 1);
    for (int call = 0; call < 5; call++)
      assertThatThrownBy(() -> client.confirm(7, slot())).isInstanceOf(RemoteHotelException.class);

    assertThatThrownBy(() -> client.confirm(7, slot()))
        .hasMessageContaining("circuit breaker is open");
    client.release(7, slot());

    server.verify();
  }

  @Test
  void actualHttpReadTimeoutIsBoundedAndRetriesAreLimited() throws Exception {
    AtomicInteger received = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var executor = Executors.newCachedThreadPool()) {
      server.setExecutor(executor);
      server.createContext(
          "/api/rooms/7/confirm-availability",
          exchange -> {
            received.incrementAndGet();
            try {
              Thread.sleep(700);
              exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      server.start();
      var factory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
      factory.setReadTimeout(Duration.ofMillis(100));
      HttpHotelClient client =
          client(
              RestClient.builder().requestFactory(factory),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              2);
      long started = System.nanoTime();

      assertThatThrownBy(() -> client.confirm(7, slot()))
          .isInstanceOfSatisfying(
              RemoteHotelException.class, error -> assertThat(error.status()).isEqualTo(504));

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      assertThat(received.get()).isBetween(1, 2);
    } finally {
      server.stop(0);
    }
  }

  private static HttpHotelClient client(RestClient.Builder builder, String baseUrl, int attempts) {
    return new HttpHotelClient(builder, tokens(), baseUrl, attempts, 1);
  }

  private static JwtTokens tokens() {
    return new JwtTokens(
        "test-user-key-with-at-least-thirty-two-bytes-012345",
        "test-internal-key-with-at-least-thirty-two-bytes-678901");
  }

  private static HotelClient.SlotRequest slot() {
    return new HotelClient.SlotRequest(
        UUID.randomUUID(), 42, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2));
  }
}
