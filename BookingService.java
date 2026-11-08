package com.example.hotel.booking.reservation;

import com.example.hotel.booking.client.HotelClient;
import com.example.hotel.booking.client.RemoteHotelException;
import com.example.hotel.common.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Saga coordinator: database state is committed before each remote operation. */
@Service
public class BookingService {
  private static final Logger log = LoggerFactory.getLogger(BookingService.class);
  private final BookingStateService state;
  private final HotelClient hotel;

  public BookingService(BookingStateService state, HotelClient hotel) {
    this.state = state;
    this.hotel = hotel;
  }

  public BookingDtos.Prepared create(long userId, BookingDtos.CreateRequest request) {
    BookingDtos.Prepared prepared = state.prepare(userId, request);
    if (!prepared.created()) return prepared;
    long id = prepared.booking().id();
    try (MDC.MDCCloseable ignored = MDC.putCloseable("bookingId", Long.toString(id))) {
      List<Long> roomIds = candidates(request);
      for (Long roomId : roomIds) {
        if (!state.assignRoom(id, roomId)) return cancelledDuringCreation(id);
        try {
          hotel.confirm(roomId, slot(prepared.booking()));
        } catch (RemoteHotelException error) {
          if (error.status() == 409) {
            if (!state.clearConflictingRoom(id, roomId)) return cancelledDuringCreation(id);
            log.info("event=room_conflict bookingId={} roomId={}", id, roomId);
            continue;
          }
          throw error;
        }
        BookingDtos.BookingView result = state.confirm(id);
        if (result.status() != Booking.Status.CONFIRMED) return cancelledDuringCreation(id);
        return new BookingDtos.Prepared(result, true);
      }
      state.cancel(id, "NO_AVAILABLE_ROOMS");
      throw new ApiException(
          409, "NO_AVAILABLE_ROOMS", "На выбранные даты нет свободного номера; bookingId=" + id);
    } catch (RemoteHotelException error) {
      state.cancel(id, error.status() == 504 ? "HOTEL_TIMEOUT" : "HOTEL_ERROR");
      compensate(id);
      int status = error.status() == 404 ? 404 : error.status() == 504 ? 504 : 503;
      throw new ApiException(
          status,
          "BOOKING_CANCELLED",
          "Бронирование отменено из-за ошибки Hotel Service; bookingId=" + id);
    }
  }

  public void cancelOwn(long userId, long id) {
    state.getOwn(userId, id);
    state.cancel(id, "USER_CANCELLED");
    compensate(id);
  }

  public void compensate(long id) {
    BookingDtos.BookingView booking = state.get(id);
    if (!booking.compensationPending() || booking.roomId() == null) return;
    try (MDC.MDCCloseable ignored = MDC.putCloseable("bookingId", Long.toString(id))) {
      hotel.release(booking.roomId(), slot(booking));
      state.compensationCompleted(id);
    } catch (RemoteHotelException error) {
      // Keep the outbox-style flag persisted. The recovery worker retries after Hotel recovers.
      log.warn(
          "event=compensation_deferred bookingId={} roomId={} remoteStatus={}",
          id,
          booking.roomId(),
          error.status());
    }
  }

  private BookingDtos.Prepared cancelledDuringCreation(long id) {
    compensate(id);
    return new BookingDtos.Prepared(state.get(id), true);
  }

  private List<Long> candidates(BookingDtos.CreateRequest request) {
    if (!request.autoSelect()) return List.of(request.roomId());
    return hotel.recommend(request.startDate(), request.endDate(), request.hotelId()).stream()
        .sorted(
            java.util.Comparator.comparingLong(HotelClient.RoomView::timesBooked)
                .thenComparingLong(HotelClient.RoomView::id))
        .map(HotelClient.RoomView::id)
        .toList();
  }

  private static HotelClient.SlotRequest slot(BookingDtos.BookingView booking) {
    return new HotelClient.SlotRequest(
        booking.requestId(), booking.id(), booking.startDate(), booking.endDate());
  }
}
