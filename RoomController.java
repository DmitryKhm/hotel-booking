package com.example.hotel.hotel.api;

import com.example.hotel.hotel.api.HotelDtos.*;
import com.example.hotel.hotel.service.HotelCatalogService;
import com.example.hotel.hotel.service.RoomAllocationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@Validated
public class RoomController {
  private final HotelCatalogService catalog;
  private final RoomAllocationService allocation;

  public RoomController(HotelCatalogService catalog, RoomAllocationService allocation) {
    this.catalog = catalog;
    this.allocation = allocation;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  @Operation(summary = "Find operational rooms free for [startDate, endDate)")
  public PageView<RoomView> list(
      @RequestParam(required = false) Long hotelId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "id") String sort) {
    return catalog.listRooms(hotelId, startDate, endDate, page, size, sort);
  }

  @GetMapping("/recommend")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'INTERNAL')")
  @Operation(summary = "Recommend free rooms ordered by timesBooked, then id")
  public List<RoomView> recommend(
      @RequestParam(required = false) Long hotelId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return catalog.recommend(hotelId, startDate, endDate);
  }

  @GetMapping("/inventory")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List all non-deleted rooms, including rooms under maintenance")
  public PageView<RoomView> inventory(
      @RequestParam(required = false) Long hotelId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return catalog.inventory(hotelId, page, size);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public RoomView get(@PathVariable Long id) {
    return catalog.getRoom(id);
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RoomView> create(@Valid @RequestBody RoomInput input) {
    RoomView room = catalog.createRoom(input);
    return ResponseEntity.created(URI.create("/api/rooms/" + room.id())).body(room);
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView update(@PathVariable Long id, @Valid @RequestBody RoomPatch input) {
    return catalog.updateRoom(id, input);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView replace(@PathVariable Long id, @Valid @RequestBody RoomInput input) {
    if (!catalog.getRoom(id).hotelId().equals(input.hotelId())) {
      throw new com.example.hotel.common.ApiException(
          400, "HOTEL_IMMUTABLE", "A room cannot be moved to another hotel");
    }
    return catalog.updateRoom(id, new RoomPatch(input.number(), input.available()));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    catalog.deleteRoom(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/confirm-availability")
  @PreAuthorize("hasRole('INTERNAL')")
  @Operation(summary = "Internal: atomically reserve a date interval; requestId is idempotent")
  public SlotView confirm(@PathVariable Long id, @Valid @RequestBody SlotRequest request) {
    return allocation.confirm(id, request);
  }

  @PostMapping("/{id}/release")
  @PreAuthorize("hasRole('INTERNAL')")
  @Operation(summary = "Internal: compensate a reservation and persist a replay tombstone")
  public ResponseEntity<Void> release(
      @PathVariable Long id, @Valid @RequestBody SlotRequest request) {
    allocation.release(id, request);
    return ResponseEntity.noContent().build();
  }
}
