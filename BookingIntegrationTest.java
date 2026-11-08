package com.example.hotel.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.booking.client.HotelClient;
import com.example.hotel.booking.client.RemoteHotelException;
import com.example.hotel.booking.reservation.*;
import com.example.hotel.booking.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "booking.recovery.enabled=false",
      "hotel.client.discovery-enabled=false",
      "spring.datasource.url=jdbc:h2:mem:booking-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired BookingRepository bookings;
  @Autowired UserRepository users;
  @Autowired UserService userService;
  @Autowired BookingService service;
  @Autowired BookingStateService state;
  @MockitoBean HotelClient hotel;
  private long userId;

  @BeforeEach
  void setUp() {
    bookings.deleteAll();
    users.deleteAll();
    userId =
        userService.register(new UserDtos.RegisterRequest("traveller", "Password123!")).user().id();
  }

  @Test
  void createsConfirmedBookingAndReplaysWithoutSecondRemoteCall() throws Exception {
    var request = request(UUID.randomUUID(), 101L, false);
    String response =
        mvc.perform(
                post("/booking")
                    .with(as(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = mapper.readTree(response).get("id").asLong();

    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));

    assertThat(bookings.count()).isEqualTo(1);
    verify(hotel, times(1)).confirm(eq(101L), any());
  }

  @Test
  void rejectsReuseOfIdempotencyKeyWithDifferentPayload() throws Exception {
    UUID key = UUID.randomUUID();
    service.create(userId, request(key, 101L, false));
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(key, 102L, false))))
        .andExpect(status().isConflict());
    assertThat(bookings.count()).isEqualTo(1);
  }

  @Test
  void remoteFailureCancelsAndCompensates() throws Exception {
    doThrow(new RemoteHotelException(503, "unavailable", true))
        .when(hotel)
        .confirm(anyLong(), any());
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(UUID.randomUUID(), 101L, false))))
        .andExpect(status().isServiceUnavailable());

    Booking stored = bookings.findAll().getFirst();
    assertThat(stored.getStatus()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(stored.isCompensationPending()).isFalse();
    verify(hotel).release(eq(101L), any());
  }

  @Test
  void timeoutPersistsCompensationUntilHotelRecovers() throws Exception {
    doThrow(new RemoteHotelException(504, "timeout after admission", true))
        .when(hotel)
        .confirm(anyLong(), any());
    doThrow(new RemoteHotelException(503, "still down", true))
        .when(hotel)
        .release(anyLong(), any());
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(UUID.randomUUID(), 101L, false))))
        .andExpect(status().isGatewayTimeout());
    Booking stored = bookings.findAll().getFirst();
    assertThat(stored.getStatus()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(stored.isCompensationPending()).isTrue();

    doNothing().when(hotel).release(anyLong(), any());
    service.compensate(stored.getId());

    assertThat(state.get(stored.getId()).compensationPending()).isFalse();
    verify(hotel, times(2)).release(eq(101L), any());
  }

  @Test
  void automaticSelectionIgnoresProvidedRoomIdAtHttpBoundary() throws Exception {
    when(hotel.recommend(any(), any(), any()))
        .thenReturn(List.of(new HotelClient.RoomView(101, 1, "Hotel", "101", true, 0)));
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(UUID.randomUUID(), -99L, true))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roomId").value(101));
  }

  @Test
  void autoSelectionUsesLeastBookedThenIdAndSkipsConcurrentConflict() {
    when(hotel.recommend(any(), any(), any()))
        .thenReturn(
            List.of(
                new HotelClient.RoomView(103, 1, "Hotel", "103", true, 5),
                new HotelClient.RoomView(102, 1, "Hotel", "102", true, 0),
                new HotelClient.RoomView(101, 1, "Hotel", "101", true, 0)));
    doThrow(new RemoteHotelException(409, "concurrent booking", false))
        .when(hotel)
        .confirm(eq(101L), any());

    var result = service.create(userId, request(UUID.randomUUID(), 999L, true));

    assertThat(result.booking().status()).isEqualTo(Booking.Status.CONFIRMED);
    assertThat(result.booking().roomId()).isEqualTo(102);
    var ordered = inOrder(hotel);
    ordered.verify(hotel).recommend(any(), any(), any());
    ordered.verify(hotel).confirm(eq(101L), any());
    ordered.verify(hotel).confirm(eq(102L), any());
    verify(hotel, never()).confirm(eq(103L), any());
  }

  @Test
  void unavailableRoomReturns409AndLeavesCancelledRecord() throws Exception {
    doThrow(new RemoteHotelException(409, "overlap", false)).when(hotel).confirm(anyLong(), any());
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(UUID.randomUUID(), 101L, false))))
        .andExpect(status().isConflict());
    assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(Booking.Status.CANCELLED);
  }

  @Test
  void bookingHistoryAndIndividualResourcesArePrivate() throws Exception {
    long id = service.create(userId, request(UUID.randomUUID(), 101L, false)).booking().id();
    long other =
        userService.register(new UserDtos.RegisterRequest("another", "Password123!")).user().id();
    mvc.perform(get("/bookings").with(as(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/booking/{id}", id).with(as(other))).andExpect(status().isNotFound());
    mvc.perform(delete("/booking/{id}", id).with(as(other))).andExpect(status().isNotFound());
    assertThat(state.get(id).status()).isEqualTo(Booking.Status.CONFIRMED);
  }

  @Test
  void cancellationIsIdempotent() throws Exception {
    long id = service.create(userId, request(UUID.randomUUID(), 101L, false)).booking().id();
    mvc.perform(delete("/booking/{id}", id).with(as(userId))).andExpect(status().isNoContent());
    mvc.perform(delete("/booking/{id}", id).with(as(userId))).andExpect(status().isNoContent());
    assertThat(state.get(id).status()).isEqualTo(Booking.Status.CANCELLED);
    verify(hotel, times(1)).release(eq(101L), any());
  }

  @Test
  void enforcesAuthenticationRolesAndDateValidation() throws Exception {
    mvc.perform(get("/bookings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/user").with(as(userId))).andExpect(status().isForbidden());
    mvc.perform(get("/bookings").param("sort", "missingProperty").with(as(userId)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/user")
                .with(
                    jwt()
                        .jwt(jwt -> jwt.subject(Long.toString(userId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].password").doesNotExist());
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    var invalid =
        new BookingDtos.CreateRequest(UUID.randomUUID(), 101L, null, false, tomorrow, tomorrow);
    mvc.perform(
            post("/booking")
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
    assertThat(bookings.count()).isZero();
  }

  @Test
  void registrationIssuesWorkingJwtAndStoresOnlyPasswordHash() throws Exception {
    String response =
        mvc.perform(
                post("/user/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"freshuser\",\"password\":\"Password123!\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.role").value("USER"))
            .andExpect(jsonPath("$.expiresIn").value(3600))
            .andExpect(jsonPath("$.user.password").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = mapper.readTree(response).get("accessToken").asText();
    mvc.perform(get("/bookings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    assertThat(users.findByUsername("freshuser").orElseThrow().getPassword())
        .startsWith("$2")
        .isNotEqualTo("Password123!");
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"freshuser\",\"password\":\"Password123!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isString());
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"freshuser\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void administratorCanCreateUpdateAndDeleteUsers() throws Exception {
    String created =
        mvc.perform(
                post("/user")
                    .with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"managed\",\"password\":\"Password123!\",\"role\":\"USER\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.password").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = mapper.readTree(created).get("id").asLong();
    mvc.perform(
            patch("/user")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + id + ",\"username\":\"renamed\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
    mvc.perform(get("/user/{id}", id).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("renamed"));
    mvc.perform(delete("/user").param("id", Long.toString(id)).with(asAdmin()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/user/{id}", id).with(asAdmin())).andExpect(status().isNotFound());
  }

  @Test
  void concurrentDuplicateRequestsCreateOneBookingAndOneRemoteAdmission() throws Exception {
    var request = request(UUID.randomUUID(), 101L, false);
    int clients = 8;
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(clients)) {
      List<Future<BookingDtos.Prepared>> calls = new ArrayList<>();
      for (int i = 0; i < clients; i++)
        calls.add(
            executor.submit(
                () -> {
                  start.await();
                  return service.create(userId, request);
                }));
      start.countDown();
      List<Long> ids = new ArrayList<>();
      for (var call : calls) ids.add(call.get(15, TimeUnit.SECONDS).booking().id());
      assertThat(ids).containsOnly(ids.getFirst());
    }
    assertThat(bookings.count()).isEqualTo(1);
    verify(hotel, times(1)).confirm(eq(101L), any());
  }

  @Test
  void concurrentCancellationCannotBeOverwrittenByLateConfirmation() throws Exception {
    CountDownLatch remoteEntered = new CountDownLatch(1);
    CountDownLatch responseAllowed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              remoteEntered.countDown();
              if (!responseAllowed.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Test synchronization timeout");
              return null;
            })
        .when(hotel)
        .confirm(anyLong(), any());
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<BookingDtos.Prepared> creation =
          executor.submit(() -> service.create(userId, request(UUID.randomUUID(), 101L, false)));
      assertThat(remoteEntered.await(10, TimeUnit.SECONDS)).isTrue();
      long id = bookings.findAll().getFirst().getId();
      try {
        service.cancelOwn(userId, id);
      } finally {
        responseAllowed.countDown();
      }
      assertThat(creation.get(10, TimeUnit.SECONDS).booking().status())
          .isEqualTo(Booking.Status.CANCELLED);
      assertThat(state.get(id).status()).isEqualTo(Booking.Status.CANCELLED);
      verify(hotel).release(eq(101L), any());
    }
  }

  @Test
  void recoveryCancelsStalePendingAndReleasesItsPersistedSlot() {
    long id = state.prepare(userId, request(UUID.randomUUID(), 101L, false)).booking().id();
    state.assignRoom(id, 101L);
    state.cancelStale(id, Instant.now().plusSeconds(1));
    service.compensate(id);
    assertThat(state.get(id).status()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(state.get(id).compensationPending()).isFalse();
    verify(hotel).release(eq(101L), any());
  }

  private static BookingDtos.CreateRequest request(UUID key, Long room, boolean autoSelect) {
    return new BookingDtos.CreateRequest(
        key, room, null, autoSelect, LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
  }

  private static JwtRequestPostProcessor as(long userId) {
    return jwt()
        .jwt(jwt -> jwt.subject(Long.toString(userId)))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private JwtRequestPostProcessor asAdmin() {
    return jwt()
        .jwt(jwt -> jwt.subject(Long.toString(userId)))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
