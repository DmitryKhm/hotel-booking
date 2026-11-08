package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.RoomReservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<RoomReservation, Long> {
  Optional<RoomReservation> findByRoomIdAndRequestId(Long roomId, UUID requestId);

  @Query(
      """
            select count(s) > 0 from RoomReservation s where s.room.id = :roomId
              and s.status = com.example.hotel.hotel.domain.ReservationStatus.CONFIRMED
              and s.startDate < :endDate and s.endDate > :startDate
            """)
  boolean overlaps(Long roomId, LocalDate startDate, LocalDate endDate);

  @Query(
      """
            select s from RoomReservation s where s.room.id = :roomId
              and s.status = com.example.hotel.hotel.domain.ReservationStatus.CONFIRMED
              and s.startDate < :endDate and s.endDate > :startDate
            """)
  List<RoomReservation> findOverlapping(Long roomId, LocalDate startDate, LocalDate endDate);

  @Query(
      "select count(s) > 0 from RoomReservation s where s.room.id = :roomId and s.status = com.example.hotel.hotel.domain.ReservationStatus.CONFIRMED and s.endDate > :today")
  boolean hasUpcomingReservation(Long roomId, LocalDate today);
}
