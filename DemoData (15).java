package com.example.hotel.hotel.config;

import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.ReservationStatus;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.ReservationRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true", matchIfMissing = true)
class DemoData implements ApplicationRunner {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  DemoData(HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.reservations = reservations;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (hotels.count() > 0) {
      return;
    }
    Hotel river = hotels.save(new Hotel("River Hotel", "Moscow, Riverside 10"));
    Hotel forest = hotels.save(new Hotel("Forest Hotel", "Kazan, Park Street 5"));
    Room first = rooms.save(new Room(river, "101", true));
    rooms.save(new Room(river, "102", true));
    Room third = rooms.save(new Room(river, "103", true));
    rooms.save(new Room(river, "104", false));
    rooms.save(new Room(forest, "201", true));
    rooms.save(new Room(forest, "202", true));
    historicalStay(first, -1001L, 20);
    historicalStay(first, -1002L, 10);
    historicalStay(third, -1003L, 15);
  }

  private void historicalStay(Room room, Long bookingId, int daysAgo) {
    LocalDate start = LocalDate.now().minusDays(daysAgo);
    reservations.save(
        new RoomReservation(
            room,
            UUID.randomUUID(),
            bookingId,
            start,
            start.plusDays(2),
            ReservationStatus.CONFIRMED));
    room.reserved();
  }
}
