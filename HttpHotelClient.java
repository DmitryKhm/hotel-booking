package com.example.hotel.booking.client;

import com.example.hotel.common.JwtTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpHotelClient implements HotelClient {
  private static final Logger log = LoggerFactory.getLogger(HttpHotelClient.class);
  private final RestClient client;
  private final JwtTokens tokens;
  private final int attempts;
  private final long initialBackoffMs;
  private final CircuitBreaker circuitBreaker;

  public HttpHotelClient(
      @Qualifier("hotelRestClientBuilder") RestClient.Builder builder,
      JwtTokens tokens,
      @Value("${hotel.client.base-url:http://hotel-service}") String baseUrl,
      @Value("${hotel.client.attempts:3}") int attempts,
      @Value("${hotel.client.initial-backoff-ms:100}") long initialBackoffMs) {
    this.client = builder.baseUrl(baseUrl).build();
    this.tokens = tokens;
    this.attempts = Math.max(1, Math.min(attempts, 5));
    this.initialBackoffMs = Math.max(0, Math.min(initialBackoffMs, 1000));
    this.circuitBreaker =
        CircuitBreaker.of(
            "hotel",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreException(
                    error -> error instanceof RemoteHotelException remote && !remote.retryable())
                .build());
  }

  @Override
  public List<RoomView> recommend(LocalDate startDate, LocalDate endDate, Long hotelId) {
    return resilient(
        () -> {
          RoomView[] rooms =
              client
                  .get()
                  .uri(
                      builder ->
                          builder
                              .path("/api/rooms/recommend")
                              .queryParam("startDate", startDate)
                              .queryParam("endDate", endDate)
                              .queryParamIfPresent("hotelId", Optional.ofNullable(hotelId))
                              .build())
                  .headers(
                      headers -> {
                        headers.setBearerAuth(tokens.issueInternal());
                        headers.set("X-Request-Id", traceId());
                      })
                  .retrieve()
                  .body(RoomView[].class);
          return rooms == null ? List.of() : Arrays.asList(rooms);
        });
  }

  @Override
  public void confirm(long roomId, SlotRequest request) {
    resilient(
        () -> {
          post(roomId, "confirm-availability", request);
          return null;
        });
  }

  @Override
  public void release(long roomId, SlotRequest request) {
    // Compensation deliberately bypasses the admission circuit breaker: it must remain possible
    // to release an already admitted reservation while new bookings are short-circuited.
    try {
      retry(
          () -> {
            post(roomId, "release", request);
            return null;
          });
    } catch (RemoteHotelException error) {
      // A deleted/nonexistent room cannot contain an outstanding slot.
      if (error.status() != 404) throw error;
    }
  }

  private void post(long roomId, String operation, SlotRequest request) {
    client
        .post()
        .uri("/api/rooms/{id}/{operation}", roomId, operation)
        .headers(
            headers -> {
              headers.setBearerAuth(tokens.issueInternal());
              headers.set("X-Request-Id", traceId());
              headers.set("X-Booking-Id", Long.toString(request.bookingId()));
            })
        .body(request)
        .retrieve()
        .toBodilessEntity();
  }

  private <T> T resilient(Supplier<T> action) {
    try {
      return circuitBreaker.executeSupplier(() -> retry(action));
    } catch (CallNotPermittedException error) {
      throw new RemoteHotelException(503, "Hotel circuit breaker is open", true, error);
    }
  }

  private <T> T retry(Supplier<T> action) {
    for (int attempt = 1; ; attempt++) {
      try {
        return action.get();
      } catch (RestClientResponseException error) {
        int status = error.getStatusCode().value();
        RemoteHotelException mapped =
            new RemoteHotelException(
                status, "Hotel returned HTTP " + status, status >= 500 || status == 429, error);
        if (!mapped.retryable() || attempt >= attempts) throw mapped;
        backoff(attempt, status);
      } catch (RestClientException error) {
        int status = isTimeout(error) ? 504 : 503;
        if (attempt >= attempts)
          throw new RemoteHotelException(status, "Hotel request failed", true, error);
        backoff(attempt, status);
      }
    }
  }

  private void backoff(int attempt, int status) {
    long delay = initialBackoffMs * (1L << (attempt - 1));
    log.warn("event=hotel_retry attempt={} status={} backoffMs={}", attempt, status, delay);
    try {
      Thread.sleep(delay);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RemoteHotelException(503, "Hotel retry interrupted", true, error);
    }
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof HttpTimeoutException
          || current instanceof SocketTimeoutException
          || current instanceof java.util.concurrent.TimeoutException) return true;
    }
    return false;
  }

  private static String traceId() {
    String value = MDC.get("traceId");
    return value == null ? java.util.UUID.randomUUID().toString() : value;
  }
}
