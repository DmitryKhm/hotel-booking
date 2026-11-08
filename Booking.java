package com.example.hotel.booking.reservation;

import com.example.hotel.booking.user.UserAccount;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "bookings",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_booking_request",
            columnNames = {"user_id", "request_id"}),
    indexes = {
      @Index(name = "ix_booking_user_created", columnList = "user_id,created_at"),
      @Index(name = "ix_booking_recovery", columnList = "status,updated_at"),
      @Index(name = "ix_booking_compensation", columnList = "compensation_pending")
    })
public class Booking {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  @Column(name = "request_id", nullable = false)
  private UUID requestId;

  private Long requestedRoomId;
  private Long requestedHotelId;

  @Column(nullable = false)
  private boolean autoSelect;

  private Long roomId;

  @Column(nullable = false)
  private LocalDate startDate;

  @Column(nullable = false)
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "compensation_pending", nullable = false)
  private boolean compensationPending;

  @Column(length = 128)
  private String cancelReason;

  @Version private long version;

  protected Booking() {}

  public Booking(UserAccount user, BookingDtos.CreateRequest request, Instant now) {
    this.user = user;
    this.requestId = request.requestId();
    this.autoSelect = request.autoSelect();
    this.requestedRoomId = request.autoSelect() ? null : request.roomId();
    this.requestedHotelId = request.hotelId();
    this.startDate = request.startDate();
    this.endDate = request.endDate();
    this.status = Status.PENDING;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public UserAccount getUser() {
    return user;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public Long getRequestedRoomId() {
    return requestedRoomId;
  }

  public Long getRequestedHotelId() {
    return requestedHotelId;
  }

  public boolean isAutoSelect() {
    return autoSelect;
  }

  public Long getRoomId() {
    return roomId;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public boolean isCompensationPending() {
    return compensationPending;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public void assign(Long roomId) {
    this.roomId = roomId;
    this.updatedAt = Instant.now();
  }

  public void confirm() {
    this.status = Status.CONFIRMED;
    this.updatedAt = Instant.now();
  }

  public void cancel(String reason) {
    this.status = Status.CANCELLED;
    this.cancelReason = reason;
    this.compensationPending = roomId != null;
    this.updatedAt = Instant.now();
  }

  public void compensationCompleted() {
    this.compensationPending = false;
    this.updatedAt = Instant.now();
  }

  public enum Status {
    PENDING,
    CONFIRMED,
    CANCELLED
  }
}
