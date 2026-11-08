package com.example.hotel.booking.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class BookingController {
  private final BookingService service;
  private final BookingStateService state;

  public BookingController(BookingService service, BookingStateService state) {
    this.service = service;
    this.state = state;
  }

  @PostMapping("/booking")
  public ResponseEntity<BookingDtos.BookingView> create(
      Authentication auth, @Valid @RequestBody BookingDtos.CreateRequest request) {
    BookingDtos.Prepared result = service.create(userId(auth), request);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.booking());
  }

  @GetMapping("/bookings")
  public Page<BookingDtos.BookingView> list(
      Authentication auth,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return state.listOwn(userId(auth), pageable);
  }

  @GetMapping("/booking/{id}")
  public BookingDtos.BookingView get(Authentication auth, @PathVariable @Positive long id) {
    return state.getOwn(userId(auth), id);
  }

  @DeleteMapping("/booking/{id}")
  public ResponseEntity<Void> cancel(Authentication auth, @PathVariable @Positive long id) {
    service.cancelOwn(userId(auth), id);
    return ResponseEntity.noContent().build();
  }

  private static long userId(Authentication authentication) {
    return Long.parseLong(authentication.getName());
  }
}
