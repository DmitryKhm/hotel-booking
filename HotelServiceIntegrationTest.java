package com.example.hotel.hotel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.common.ApiException;
import com.example.hotel.hotel.api.HotelDtos.*;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.ReservationRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.service.HotelCatalogService;
import com.example.hotel.hotel.service.HotelStatisticsService;
import com.example.hotel.hotel.service.RoomAllocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties = {
      "app.demo-data=false",
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:hotel-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
class HotelServiceIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired HotelCatalogService catalog;
  @Autowired RoomAllocationService allocation;
  @Autowired HotelStatisticsService statistics;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired ReservationRepository reservations;
  Long hotelId;
  Long roomId;
  Long secondRoomId;
  final LocalDate start = LocalDate.now().plusDays(10);
  final LocalDate end = start.plusDays(3);

  @BeforeEach
  void seed() {
    reservations.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    hotelId = catalog.createHotel(new HotelInput("Test Hotel", "Test address")).id();
    roomId = catalog.createRoom(new RoomInput(hotelId, "101", true)).id();
    secondRoomId = catalog.createRoom(new RoomInput(hotelId, "102", true)).id();
  }

  @Test
  void concurrentOverlappingReservationsHaveExactlyOneWinner() throws Exception {
    int clients = 12;
    CountDownLatch ready = new CountDownLatch(clients);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(clients)) {
      for (int i = 0; i < clients; i++) {
        final long bookingId = i + 1;
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start timeout");
                  }
                  try {
                    allocation.confirm(roomId, request(bookingId));
                    return true;
                  } catch (ApiException conflict) {
                    assertThat(conflict).hasMessageContaining("already reserved");
                    return false;
                  }
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int winners = 0;
      for (Future<Boolean> result : results) {
        if (result.get(20, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
    }
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(catalog.getRoom(roomId).timesBooked()).isEqualTo(1);
  }

  @Test
  void parallelDeliveryOfSameRequestDoesNotDuplicateReservationOrCounter() throws Exception {
    SlotRequest same = request(1L);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<SlotView>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(executor.submit(() -> allocation.confirm(roomId, same)));
      }
      for (var result : results) {
        assertThat(result.get(20, TimeUnit.SECONDS).status()).isEqualTo("CONFIRMED");
      }
    }
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(catalog.getRoom(roomId).timesBooked()).isEqualTo(1);
  }

  @Test
  void adjacentDateIntervalsCanBothBeReserved() {
    allocation.confirm(roomId, request(1L));
    allocation.confirm(roomId, new SlotRequest(UUID.randomUUID(), 2L, end, end.plusDays(2)));
    assertThat(reservations.count()).isEqualTo(2);
    assertThat(catalog.getRoom(roomId).timesBooked()).isEqualTo(2);
  }

  @Test
  void compensationBeforeDelayedConfirmationPersistsATombstone() {
    SlotRequest request = request(1L);
    allocation.release(roomId, request);
    assertThatThrownBy(() -> allocation.confirm(roomId, request))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("compensated");
    assertThat(catalog.getRoom(roomId).timesBooked()).isZero();
    assertThat(catalog.recommend(hotelId, start, end)).extracting(RoomView::id).contains(roomId);
  }

  @Test
  void repeatedCompensationRestoresAvailabilityAndCounterOnlyOnce() {
    SlotRequest request = request(1L);
    allocation.confirm(roomId, request);
    allocation.release(roomId, request);
    allocation.release(roomId, request);
    assertThat(catalog.getRoom(roomId).timesBooked()).isZero();
    assertThat(catalog.recommend(hotelId, start, end)).hasSize(2);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void reusingRequestIdWithDifferentDatesIsRejected() {
    SlotRequest first = request(1L);
    allocation.confirm(roomId, first);
    SlotRequest changed =
        new SlotRequest(first.requestId(), 1L, start.plusDays(1), end.plusDays(1));
    assertThatThrownBy(() -> allocation.confirm(roomId, changed))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("another payload");
    assertThatThrownBy(() -> allocation.release(roomId, changed)).isInstanceOf(ApiException.class);
    assertThat(catalog.getRoom(roomId).timesBooked()).isEqualTo(1);
  }

  @Test
  void recommendationsUseBookingCountThenIdAndExcludeBusyAndMaintenanceRooms() {
    allocation.confirm(
        roomId, new SlotRequest(UUID.randomUUID(), 1L, start.minusDays(5), start.minusDays(2)));
    assertThat(catalog.recommend(hotelId, start, end))
        .extracting(RoomView::id)
        .containsExactly(secondRoomId, roomId);
    allocation.confirm(secondRoomId, request(2L));
    assertThat(catalog.recommend(hotelId, start, end))
        .extracting(RoomView::id)
        .containsExactly(roomId);
    catalog.updateRoom(roomId, new RoomPatch(null, false));
    assertThat(catalog.recommend(hotelId, start, end)).isEmpty();
  }

  @Test
  void statisticsClipsReservedNightsToRequestedPeriod() {
    allocation.confirm(roomId, request(1L));
    Statistics result = statistics.get(hotelId, start.plusDays(1), end.plusDays(2));
    assertThat(result.bookedNights()).isEqualTo(2);
    assertThat(result.capacityNights()).isEqualTo(8);
    assertThat(result.occupancyPercent()).isEqualTo(25.0);
  }

  @Test
  void anonymousIs401AndUserCannotAdministerOrInvokeInternalOperations() throws Exception {
    mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/hotels")
                .with(role("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new HotelInput("Hotel", "Address"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", roomId)
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(1L))))
        .andExpect(status().isForbidden());
  }

  @Test
  void internalHttpProtocolIsIdempotentAndConflictsOnOverlap() throws Exception {
    String body = json.writeValueAsString(request(1L));
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", roomId)
                .with(role("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", roomId)
                .with(role("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", roomId)
                .with(role("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(2L))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/rooms/{id}/release", roomId)
                .with(role("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
  }

  @Test
  void invalidDatesAndPagingAre400() throws Exception {
    mvc.perform(
            get("/api/rooms")
                .with(role("USER"))
                .param("startDate", end.toString())
                .param("endDate", start.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/hotels").with(role("USER")).param("size", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/hotels")
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"address\":\"address\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void hotelAndRoomCrudAndFiltersWork() throws Exception {
    mvc.perform(get("/api/hotels").with(role("USER")).param("search", "test").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Test Hotel"));
    mvc.perform(
            patch("/api/rooms/{id}", roomId)
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"number\":\"Suite\",\"available\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value("Suite"));
    mvc.perform(get("/api/rooms/inventory").with(role("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(delete("/api/rooms/{id}", roomId).with(role("ADMIN")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/rooms/{id}", roomId).with(role("USER"))).andExpect(status().isNotFound());
    mvc.perform(delete("/api/hotels/{id}", hotelId).with(role("ADMIN")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/hotels/{id}", hotelId).with(role("USER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletionCannotRemoveUpcomingReservations() {
    allocation.confirm(roomId, request(1L));
    assertThatThrownBy(() -> catalog.deleteRoom(roomId))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Cancel upcoming");
    assertThatThrownBy(() -> catalog.deleteHotel(hotelId)).isInstanceOf(ApiException.class);
    assertThat(catalog.getRoom(roomId)).isNotNull();
  }

  private SlotRequest request(Long bookingId) {
    return new SlotRequest(UUID.randomUUID(), bookingId, start, end);
  }

  private RequestPostProcessor role(String role) {
    return jwt()
        .jwt(token -> token.subject("1"))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
