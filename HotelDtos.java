package com.example.hotel.hotel.api;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class HotelDtos {
  private HotelDtos() {}

  public record HotelInput(
      @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 300) String address) {}

  public record HotelPatch(
      @Size(min = 1, max = 160) String name, @Size(min = 1, max = 300) String address) {}

  public record HotelView(Long id, String name, String address) {}

  public record RoomInput(
      @NotNull @Positive Long hotelId,
      @NotBlank @Size(max = 40) String number,
      @NotNull Boolean available) {}

  public record RoomPatch(@Size(min = 1, max = 40) String number, Boolean available) {}

  public record RoomView(
      Long id,
      Long hotelId,
      String hotelName,
      String number,
      boolean available,
      long timesBooked) {}

  public record SlotRequest(
      @NotNull UUID requestId,
      @NotNull @Positive Long bookingId,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record SlotView(UUID requestId, Long bookingId, Long roomId, String status) {}

  public record RoomStatistics(
      Long roomId,
      Long hotelId,
      String number,
      boolean available,
      long timesBooked,
      long reservationsInPeriod,
      long bookedNights,
      long periodNights,
      double occupancyPercent) {}

  public record Statistics(
      LocalDate startDate,
      LocalDate endDate,
      long totalRooms,
      long operationalRooms,
      long bookedNights,
      long capacityNights,
      double occupancyPercent,
      List<RoomStatistics> rooms) {}

  public record PageView<T>(
      List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageView<T> from(Page<T> page) {
      return new PageView<>(
          page.getContent(),
          page.getNumber(),
          page.getSize(),
          page.getTotalElements(),
          page.getTotalPages());
    }
  }
}
