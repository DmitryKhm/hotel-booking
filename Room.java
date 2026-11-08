package com.example.hotel.hotel.domain;

import jakarta.persistence.*;

@Entity
@Table(
    name = "rooms",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_room_number",
            columnNames = {"hotel_id", "room_number"}),
    indexes = @Index(name = "idx_room_selection", columnList = "hotel_id,available,times_booked"))
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hotel_id", nullable = false)
  private Hotel hotel;

  @Column(name = "room_number", nullable = false, length = 40)
  private String number;

  @Column(nullable = false)
  private boolean available;

  @Column(name = "times_booked", nullable = false)
  private long timesBooked;

  @Column(nullable = false)
  private boolean deleted;

  @Version private long version;

  protected Room() {}

  public Room(Hotel hotel, String number, boolean available) {
    this.hotel = hotel;
    this.number = number;
    this.available = available;
  }

  public Long getId() {
    return id;
  }

  public Hotel getHotel() {
    return hotel;
  }

  public String getNumber() {
    return number;
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public long getTimesBooked() {
    return timesBooked;
  }

  public void update(String number, boolean available) {
    this.number = number;
    this.available = available;
  }

  public void reserved() {
    timesBooked++;
  }

  public void released() {
    timesBooked = Math.max(0, timesBooked - 1);
  }

  public void delete() {
    deleted = true;
    available = false;
  }
}
