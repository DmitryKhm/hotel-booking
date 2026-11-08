package com.example.hotel.booking.reservation;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "booking.recovery.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BookingRecovery {
  private static final Logger log = LoggerFactory.getLogger(BookingRecovery.class);
  private final BookingRepository bookings;
  private final BookingStateService state;
  private final BookingService service;
  private final long pendingTtlMs;

  public BookingRecovery(
      BookingRepository bookings,
      BookingStateService state,
      BookingService service,
      @Value("${booking.recovery.pending-ttl-ms:60000}") long pendingTtlMs) {
    this.bookings = bookings;
    this.state = state;
    this.service = service;
    this.pendingTtlMs = pendingTtlMs;
  }

  @Scheduled(fixedDelayString = "${booking.recovery.interval-ms:5000}")
  public void recover() {
    Instant cutoff = Instant.now().minusMillis(pendingTtlMs);
    for (Long id : bookings.findStalePendingIds(cutoff, PageRequest.of(0, 100))) {
      try {
        state.cancelStale(id, cutoff);
      } catch (RuntimeException error) {
        log.error("event=recovery_failed bookingId={}", id, error);
      }
    }
    for (Long id : bookings.findCompensationIds(PageRequest.of(0, 100))) {
      try {
        service.compensate(id);
      } catch (RuntimeException error) {
        log.error("event=compensation_retry_failed bookingId={}", id, error);
      }
    }
  }
}
