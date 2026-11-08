package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
  @Query(
      "select h from Hotel h where h.deleted = false and (:search is null or lower(h.name) like lower(concat('%', :search, '%')) or lower(h.address) like lower(concat('%', :search, '%')))")
  Page<Hotel> search(String search, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select h from Hotel h where h.id = :id")
  Optional<Hotel> lockById(Long id);
}
