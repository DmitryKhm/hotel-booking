package com.example.hotel.hotel.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "room_reservations",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_reservation_request",
            columnNames = {"room_id", "request_id"}),
    indexes = @Index(name = "idx_slot_overlap", columnList = "room_id,status,start_date,end_date"))
public class RoomReservation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @Column(name = "request_id", nullable = false)
  private UUID requestId;

  @Column(name = "booking_id", nullable = false)
  private Long bookingId;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReservationStatus status;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  protected RoomReservation() {}

  public RoomReservation(
      Room room,
      UUID requestId,
      Long bookingId,
      LocalDate startDate,
      LocalDate endDate,
      ReservationStatus status) {
    this.room = room;
    this.requestId = requestId;
    this.bookingId = bookingId;
    this.startDate = startDate;
    this.endDate = endDate;
    this.status = status;
  }

  public Room getRoom() {
    return room;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public Long getBookingId() {
    return bookingId;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public void release() {
    status = ReservationStatus.RELEASED;
  }
}
