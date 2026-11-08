package com.example.hotel.hotel.service;

import com.example.hotel.common.ApiException;
import com.example.hotel.hotel.api.HotelDtos.*;
import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.ReservationRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HotelCatalogService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public HotelCatalogService(
      HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.reservations = reservations;
  }

  public PageView<HotelView> listHotels(String search, int page, int size, String sort) {
    if (!Set.of("id", "name", "address").contains(sort)) {
      throw new ApiException(400, "INVALID_SORT", "Hotel sort must be id, name or address");
    }
    return PageView.from(
        hotels
            .search(search, PageRequest.of(page, size, Sort.by(sort).and(Sort.by("id"))))
            .map(this::view));
  }

  public HotelView getHotel(Long id) {
    return view(activeHotel(id, false));
  }

  @Transactional
  public HotelView createHotel(HotelInput input) {
    return view(hotels.save(new Hotel(input.name().trim(), input.address().trim())));
  }

  @Transactional
  public HotelView updateHotel(Long id, HotelPatch input) {
    Hotel hotel = activeHotel(id, true);
    String name = input.name() == null ? hotel.getName() : nonBlank(input.name());
    String address = input.address() == null ? hotel.getAddress() : nonBlank(input.address());
    hotel.update(name, address);
    return view(hotel);
  }

  @Transactional
  public void deleteHotel(Long id) {
    Hotel hotel = activeHotel(id, true);
    for (Room room : rooms.findByHotelIdOrderById(id)) {
      Room locked = lockRoom(room.getId());
      ensureDeletable(locked);
      locked.delete();
    }
    hotel.delete();
  }

  public RoomView getRoom(Long id) {
    return view(activeRoom(id));
  }

  @Transactional
  public RoomView createRoom(RoomInput input) {
    Hotel hotel = activeHotel(input.hotelId(), true);
    String number = input.number().trim();
    if (rooms.existsByHotelIdAndNumber(hotel.getId(), number)) {
      throw new ApiException(
          409, "ROOM_NUMBER_EXISTS", "A room with this number already exists in the hotel");
    }
    return view(rooms.save(new Room(hotel, number, input.available())));
  }

  @Transactional
  public RoomView updateRoom(Long id, RoomPatch input) {
    Room room = lockRoom(id);
    ensureActive(room);
    String number = input.number() == null ? room.getNumber() : nonBlank(input.number());
    if (!number.equals(room.getNumber())
        && rooms.existsByHotelIdAndNumber(room.getHotel().getId(), number)) {
      throw new ApiException(
          409, "ROOM_NUMBER_EXISTS", "A room with this number already exists in the hotel");
    }
    room.update(number, input.available() == null ? room.isAvailable() : input.available());
    return view(room);
  }

  @Transactional
  public void deleteRoom(Long id) {
    Room room = lockRoom(id);
    ensureActive(room);
    ensureDeletable(room);
    room.delete();
  }

  public List<RoomView> recommend(Long hotelId, LocalDate startDate, LocalDate endDate) {
    DateRanges.validate(startDate, endDate);
    return rooms.findRecommended(hotelId, startDate, endDate).stream()
        .map(HotelCatalogService::view)
        .toList();
  }

  public PageView<RoomView> listRooms(
      Long hotelId, LocalDate startDate, LocalDate endDate, int page, int size, String sort) {
    List<RoomView> available = recommend(hotelId, startDate, endDate);
    Comparator<RoomView> order =
        switch (sort) {
          case "id" -> Comparator.comparing(RoomView::id);
          case "number" -> Comparator.comparing(RoomView::number).thenComparing(RoomView::id);
          case "timesBooked" ->
              Comparator.comparingLong(RoomView::timesBooked).thenComparing(RoomView::id);
          default ->
              throw new ApiException(
                  400, "INVALID_SORT", "Room sort must be id, number or timesBooked");
        };
    List<RoomView> sorted = available.stream().sorted(order).toList();
    long offset = (long) page * size;
    int from = (int) Math.min(offset, sorted.size());
    int to = Math.min(from + size, sorted.size());
    return PageView.from(
        new PageImpl<>(sorted.subList(from, to), PageRequest.of(page, size), sorted.size()));
  }

  public PageView<RoomView> inventory(Long hotelId, int page, int size) {
    List<RoomView> all =
        rooms.findForStatistics(hotelId).stream().map(HotelCatalogService::view).toList();
    int from = (int) Math.min((long) page * size, all.size());
    return PageView.from(
        new PageImpl<>(
            all.subList(from, Math.min(from + size, all.size())),
            PageRequest.of(page, size),
            all.size()));
  }

  private void ensureDeletable(Room room) {
    if (reservations.hasUpcomingReservation(room.getId(), LocalDate.now())) {
      throw new ApiException(
          409, "ROOM_HAS_BOOKINGS", "Cancel upcoming room bookings before deletion");
    }
  }

  private Hotel activeHotel(Long id, boolean lock) {
    Hotel hotel =
        (lock ? hotels.lockById(id) : hotels.findById(id))
            .orElseThrow(() -> new ApiException(404, "HOTEL_NOT_FOUND", "Hotel not found"));
    if (hotel.isDeleted()) {
      throw new ApiException(404, "HOTEL_NOT_FOUND", "Hotel not found");
    }
    return hotel;
  }

  private Room activeRoom(Long id) {
    Room room =
        rooms
            .findById(id)
            .orElseThrow(() -> new ApiException(404, "ROOM_NOT_FOUND", "Room not found"));
    ensureActive(room);
    return room;
  }

  private Room lockRoom(Long id) {
    return rooms
        .lockById(id)
        .orElseThrow(() -> new ApiException(404, "ROOM_NOT_FOUND", "Room not found"));
  }

  private void ensureActive(Room room) {
    if (room.isDeleted() || room.getHotel().isDeleted()) {
      throw new ApiException(404, "ROOM_NOT_FOUND", "Room not found");
    }
  }

  private String nonBlank(String value) {
    if (value.isBlank()) {
      throw new ApiException(400, "INVALID_VALUE", "Value must not be blank");
    }
    return value.trim();
  }

  private HotelView view(Hotel hotel) {
    return new HotelView(hotel.getId(), hotel.getName(), hotel.getAddress());
  }

  static RoomView view(Room room) {
    return new RoomView(
        room.getId(),
        room.getHotel().getId(),
        room.getHotel().getName(),
        room.getNumber(),
        room.isAvailable(),
        room.getTimesBooked());
  }
}
