package com.example.hotel.booking.reservation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  Optional<Booking> findByUserIdAndRequestId(long userId, UUID requestId);

  Page<Booking> findByUserId(long userId, Pageable pageable);

  boolean existsByUserId(long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id = :id")
  Optional<Booking> findLockedById(@Param("id") long id);

  @Query(
      "select b.id from Booking b where b.status = 'PENDING' and b.updatedAt < :cutoff order by b.id")
  List<Long> findStalePendingIds(@Param("cutoff") Instant cutoff, Pageable pageable);

  @Query("select b.id from Booking b where b.compensationPending = true order by b.id")
  List<Long> findCompensationIds(Pageable pageable);
}
