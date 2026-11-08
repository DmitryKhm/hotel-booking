package com.example.hotel.booking.reservation;

import com.example.hotel.booking.user.UserAccount;
import com.example.hotel.booking.user.UserRepository;
import com.example.hotel.common.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every method is a short local transaction. No HTTP call occurs while a database lock is held. */
@Service
public class BookingStateService {
  private static final Logger log = LoggerFactory.getLogger(BookingStateService.class);
  private final BookingRepository bookings;
  private final UserRepository users;

  public BookingStateService(BookingRepository bookings, UserRepository users) {
    this.bookings = bookings;
    this.users = users;
  }

  @Transactional
  public BookingDtos.Prepared prepare(long userId, BookingDtos.CreateRequest request) {
    validateStructure(request);
    // A database row lock serializes duplicate requests even across application instances.
    UserAccount user =
        users
            .findLockedById(userId)
            .orElseThrow(
                () -> new ApiException(401, "USER_NOT_FOUND", "Пользователь больше не существует"));
    var previous = bookings.findByUserIdAndRequestId(userId, request.requestId());
    if (previous.isPresent()) {
      if (!sameRequest(previous.get(), request)) {
        throw new ApiException(
            409, "IDEMPOTENCY_CONFLICT", "Этот requestId уже использован с другими параметрами");
      }
      return new BookingDtos.Prepared(BookingDtos.BookingView.from(previous.get()), false);
    }
    if (request.startDate().isBefore(LocalDate.now())) {
      throw new ApiException(400, "INVALID_DATES", "Дата заезда не может быть в прошлом");
    }
    Booking booking = bookings.saveAndFlush(new Booking(user, request, Instant.now()));
    log.info(
        "event=booking_pending bookingId={} requestId={} userId={}",
        booking.getId(),
        request.requestId(),
        userId);
    return new BookingDtos.Prepared(BookingDtos.BookingView.from(booking), true);
  }

  @Transactional
  public boolean assignRoom(long id, long roomId) {
    Booking booking = locked(id);
    if (booking.getStatus() != Booking.Status.PENDING) return false;
    booking.assign(roomId);
    return true;
  }

  @Transactional
  public boolean clearConflictingRoom(long id, long roomId) {
    Booking booking = locked(id);
    if (booking.getStatus() != Booking.Status.PENDING) return false;
    if (Objects.equals(booking.getRoomId(), roomId)) booking.assign(null);
    return true;
  }

  @Transactional
  public BookingDtos.BookingView confirm(long id) {
    Booking booking = locked(id);
    if (booking.getStatus() == Booking.Status.PENDING) {
      booking.confirm();
      log.info(
          "event=booking_confirmed bookingId={} requestId={} roomId={}",
          id,
          booking.getRequestId(),
          booking.getRoomId());
    }
    return BookingDtos.BookingView.from(booking);
  }

  @Transactional
  public BookingDtos.BookingView cancel(long id, String reason) {
    Booking booking = locked(id);
    if (booking.getStatus() != Booking.Status.CANCELLED) {
      booking.cancel(reason);
      log.info(
          "event=booking_cancelled bookingId={} requestId={} reason={} compensationPending={}",
          id,
          booking.getRequestId(),
          reason,
          booking.isCompensationPending());
    }
    return BookingDtos.BookingView.from(booking);
  }

  @Transactional
  public void cancelStale(long id, Instant cutoff) {
    Booking booking = locked(id);
    if (booking.getStatus() == Booking.Status.PENDING && booking.getUpdatedAt().isBefore(cutoff)) {
      booking.cancel("RECOVERY_PENDING_TIMEOUT");
      log.warn(
          "event=stale_booking_cancelled bookingId={} requestId={}", id, booking.getRequestId());
    }
  }

  @Transactional
  public void compensationCompleted(long id) {
    Booking booking = locked(id);
    if (booking.getStatus() == Booking.Status.CANCELLED) {
      booking.compensationCompleted();
      log.info(
          "event=compensation_completed bookingId={} requestId={}", id, booking.getRequestId());
    }
  }

  @Transactional(readOnly = true)
  public BookingDtos.BookingView get(long id) {
    return BookingDtos.BookingView.from(
        bookings.findById(id).orElseThrow(BookingStateService::notFound));
  }

  @Transactional(readOnly = true)
  public BookingDtos.BookingView getOwn(long userId, long id) {
    Booking booking = bookings.findById(id).orElseThrow(BookingStateService::notFound);
    if (booking.getUser().getId() != userId) throw notFound();
    return BookingDtos.BookingView.from(booking);
  }

  @Transactional(readOnly = true)
  public Page<BookingDtos.BookingView> listOwn(long userId, Pageable pageable) {
    pageable
        .getSort()
        .forEach(
            order -> {
              if (!java.util.Set.of("id", "createdAt", "startDate", "endDate", "status", "roomId")
                  .contains(order.getProperty())) {
                throw new ApiException(
                    400, "INVALID_SORT", "Неизвестное поле сортировки бронирований");
              }
            });
    return bookings.findByUserId(userId, pageable).map(BookingDtos.BookingView::from);
  }

  private Booking locked(long id) {
    return bookings.findLockedById(id).orElseThrow(BookingStateService::notFound);
  }

  private static ApiException notFound() {
    return new ApiException(404, "BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static boolean sameRequest(Booking previous, BookingDtos.CreateRequest request) {
    return previous.isAutoSelect() == request.autoSelect()
        && Objects.equals(
            previous.getRequestedRoomId(), request.autoSelect() ? null : request.roomId())
        && Objects.equals(previous.getRequestedHotelId(), request.hotelId())
        && previous.getStartDate().equals(request.startDate())
        && previous.getEndDate().equals(request.endDate());
  }

  private static void validateStructure(BookingDtos.CreateRequest request) {
    if (request.requestId() == null
        || request.startDate() == null
        || request.endDate() == null
        || !request.startDate().isBefore(request.endDate())
        || java.time.temporal.ChronoUnit.DAYS.between(request.startDate(), request.endDate())
            > 3650) {
      throw new ApiException(
          400, "INVALID_DATES", "Нужны requestId и даты: сегодня ≤ заезд < выезд");
    }
    if (!request.autoSelect() && (request.roomId() == null || request.roomId() <= 0)) {
      throw new ApiException(
          400, "ROOM_REQUIRED", "При autoSelect=false необходимо указать roomId");
    }
  }
}
