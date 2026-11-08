package com.example.hotel.hotel.service;

import com.example.hotel.hotel.api.HotelDtos.RoomStatistics;
import com.example.hotel.hotel.api.HotelDtos.Statistics;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.repository.ReservationRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HotelStatisticsService {
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public HotelStatisticsService(RoomRepository rooms, ReservationRepository reservations) {
    this.rooms = rooms;
    this.reservations = reservations;
  }

  public Statistics get(Long hotelId, LocalDate start, LocalDate end) {
    DateRanges.validate(start, end);
    long days = ChronoUnit.DAYS.between(start, end);
    List<RoomStatistics> rows =
        rooms.findForStatistics(hotelId).stream()
            .map(room -> statistics(room, start, end, days))
            .toList();
    long booked = rows.stream().mapToLong(RoomStatistics::bookedNights).sum();
    long operational = rows.stream().filter(RoomStatistics::available).count();
    // Historical occupancy uses all non-deleted rooms, including rooms currently under maintenance.
    long capacity = days * rows.size();
    return new Statistics(
        start, end, rows.size(), operational, booked, capacity, percent(booked, capacity), rows);
  }

  private RoomStatistics statistics(Room room, LocalDate start, LocalDate end, long days) {
    List<RoomReservation> slots = reservations.findOverlapping(room.getId(), start, end);
    long nights =
        slots.stream()
            .mapToLong(
                slot ->
                    ChronoUnit.DAYS.between(
                        slot.getStartDate().isBefore(start) ? start : slot.getStartDate(),
                        slot.getEndDate().isAfter(end) ? end : slot.getEndDate()))
            .sum();
    return new RoomStatistics(
        room.getId(),
        room.getHotel().getId(),
        room.getNumber(),
        room.isAvailable(),
        room.getTimesBooked(),
        slots.size(),
        nights,
        days,
        percent(nights, days));
  }

  private double percent(long numerator, long denominator) {
    return denominator == 0 ? 0 : Math.round(10000.0 * numerator / denominator) / 100.0;
  }
}
