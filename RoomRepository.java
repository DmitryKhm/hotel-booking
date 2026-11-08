package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Room;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id = :id")
  Optional<Room> lockById(Long id);

  List<Room> findByHotelIdOrderById(Long hotelId);

  boolean existsByHotelIdAndNumber(Long hotelId, String number);

  @Query(
      """
            select r from Room r join fetch r.hotel h
            where r.deleted = false and h.deleted = false and r.available = true
              and (:hotelId is null or h.id = :hotelId)
              and not exists (select s.id from RoomReservation s where s.room = r
                  and s.status = com.example.hotel.hotel.domain.ReservationStatus.CONFIRMED
                  and s.startDate < :endDate and s.endDate > :startDate)
            order by r.timesBooked, r.id
            """)
  List<Room> findRecommended(Long hotelId, LocalDate startDate, LocalDate endDate);

  @Query(
      "select r from Room r join fetch r.hotel h where r.deleted = false and h.deleted = false and (:hotelId is null or h.id = :hotelId) order by r.id")
  List<Room> findForStatistics(Long hotelId);
}
