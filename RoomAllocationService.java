package com.example.hotel.hotel.service;

import com.example.hotel.common.ApiException;
import com.example.hotel.hotel.api.HotelDtos.SlotRequest;
import com.example.hotel.hotel.api.HotelDtos.SlotView;
import com.example.hotel.hotel.domain.ReservationStatus;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.repository.ReservationRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the availability check and insert on the persistent room row. */
@Service
public class RoomAllocationService {
  private static final Logger log = LoggerFactory.getLogger(RoomAllocationService.class);
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public RoomAllocationService(RoomRepository rooms, ReservationRepository reservations) {
    this.rooms = rooms;
    this.reservations = reservations;
  }

  @Transactional
  public SlotView confirm(Long roomId, SlotRequest request) {
    DateRanges.validate(request.startDate(), request.endDate());
    Room room = lockRoom(roomId);
    var previous = reservations.findByRoomIdAndRequestId(roomId, request.requestId());
    if (previous.isPresent()) {
      RoomReservation reservation = previous.get();
      samePayload(reservation, request);
      if (reservation.getStatus() == ReservationStatus.RELEASED) {
        throw new ApiException(
            409, "RESERVATION_RELEASED", "This request was compensated and cannot reserve again");
      }
      log.info(
          "event=reservation_replayed bookingId={} requestId={} roomId={}",
          request.bookingId(),
          request.requestId(),
          roomId);
      return view(reservation);
    }
    if (room.isDeleted() || room.getHotel().isDeleted() || !room.isAvailable()) {
      throw new ApiException(409, "ROOM_UNAVAILABLE", "Room is not operationally available");
    }
    if (reservations.overlaps(roomId, request.startDate(), request.endDate())) {
      throw new ApiException(
          409, "ROOM_ALREADY_BOOKED", "Room is already reserved for overlapping dates");
    }
    RoomReservation reservation =
        reservations.save(
            new RoomReservation(
                room,
                request.requestId(),
                request.bookingId(),
                request.startDate(),
                request.endDate(),
                ReservationStatus.CONFIRMED));
    room.reserved();
    log.info(
        "event=reservation_confirmed bookingId={} requestId={} roomId={}",
        request.bookingId(),
        request.requestId(),
        roomId);
    return view(reservation);
  }

  @Transactional
  public void release(Long roomId, SlotRequest request) {
    DateRanges.validate(request.startDate(), request.endDate());
    Room room = lockRoom(roomId);
    var previous = reservations.findByRoomIdAndRequestId(roomId, request.requestId());
    if (previous.isEmpty()) {
      // A timeout can race the original request. Persist a tombstone before acknowledging release.
      reservations.save(
          new RoomReservation(
              room,
              request.requestId(),
              request.bookingId(),
              request.startDate(),
              request.endDate(),
              ReservationStatus.RELEASED));
    } else {
      RoomReservation reservation = previous.get();
      samePayload(reservation, request);
      if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
        reservation.release();
        room.released();
      }
    }
    log.info(
        "event=reservation_released bookingId={} requestId={} roomId={}",
        request.bookingId(),
        request.requestId(),
        roomId);
  }

  private Room lockRoom(Long id) {
    return rooms
        .lockById(id)
        .orElseThrow(() -> new ApiException(404, "ROOM_NOT_FOUND", "Room not found"));
  }

  private void samePayload(RoomReservation reservation, SlotRequest request) {
    if (!reservation.getBookingId().equals(request.bookingId())
        || !reservation.getStartDate().equals(request.startDate())
        || !reservation.getEndDate().equals(request.endDate())) {
      throw new ApiException(
          409, "IDEMPOTENCY_CONFLICT", "requestId was already used with another payload");
    }
  }

  private SlotView view(RoomReservation reservation) {
    return new SlotView(
        reservation.getRequestId(),
        reservation.getBookingId(),
        reservation.getRoom().getId(),
        reservation.getStatus().name());
  }
}
