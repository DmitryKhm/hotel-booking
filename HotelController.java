package com.example.hotel.hotel.api;

import com.example.hotel.hotel.api.HotelDtos.*;
import com.example.hotel.hotel.service.HotelCatalogService;
import com.example.hotel.hotel.service.HotelStatisticsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hotels")
@Validated
public class HotelController {
  private final HotelCatalogService catalog;
  private final HotelStatisticsService statistics;

  public HotelController(HotelCatalogService catalog, HotelStatisticsService statistics) {
    this.catalog = catalog;
    this.statistics = statistics;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public PageView<HotelView> list(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "id") String sort) {
    return catalog.listHotels(search, page, size, sort);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public HotelView get(@PathVariable Long id) {
    return catalog.getHotel(id);
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<HotelView> create(@Valid @RequestBody HotelInput input) {
    HotelView hotel = catalog.createHotel(input);
    return ResponseEntity.created(URI.create("/api/hotels/" + hotel.id())).body(hotel);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView replace(@PathVariable Long id, @Valid @RequestBody HotelInput input) {
    return catalog.updateHotel(id, new HotelPatch(input.name(), input.address()));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView update(@PathVariable Long id, @Valid @RequestBody HotelPatch input) {
    return catalog.updateHotel(id, input);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    catalog.deleteHotel(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  public Statistics statistics(
      @RequestParam(required = false) Long hotelId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return statistics.get(hotelId, startDate, endDate);
  }
}
