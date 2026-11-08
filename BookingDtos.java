package com.example.hotel.booking.reservation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class BookingDtos {
  private BookingDtos() {}

  public record CreateRequest(
      @NotNull UUID requestId,
      Long roomId,
      @Positive Long hotelId,
      boolean autoSelect,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record BookingView(
      long id,
      long userId,
      UUID requestId,
      Long roomId,
      LocalDate startDate,
      LocalDate endDate,
      Booking.Status status,
      Instant createdAt,
      boolean compensationPending,
      String cancelReason) {
    public static BookingView from(Booking booking) {
      return new BookingView(
          booking.getId(),
          booking.getUser().getId(),
          booking.getRequestId(),
          booking.getRoomId(),
          booking.getStartDate(),
          booking.getEndDate(),
          booking.getStatus(),
          booking.getCreatedAt(),
          booking.isCompensationPending(),
          booking.getCancelReason());
    }
  }

  public record Prepared(BookingView booking, boolean created) {}
}
