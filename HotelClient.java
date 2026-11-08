package com.example.hotel.booking.client;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface HotelClient {
  List<RoomView> recommend(LocalDate startDate, LocalDate endDate, Long hotelId);

  void confirm(long roomId, SlotRequest request);

  void release(long roomId, SlotRequest request);

  record RoomView(
      long id,
      long hotelId,
      String hotelName,
      String number,
      boolean available,
      long timesBooked) {}

  record SlotRequest(UUID requestId, long bookingId, LocalDate startDate, LocalDate endDate) {}
}
