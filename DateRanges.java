package com.example.hotel.hotel.service;

import com.example.hotel.common.ApiException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

final class DateRanges {
  private DateRanges() {}

  static void validate(LocalDate start, LocalDate end) {
    if (start == null || end == null || !end.isAfter(start)) {
      throw new ApiException(
          400,
          "INVALID_DATES",
          "endDate must be after startDate; intervals are [startDate, endDate)");
    }
    if (ChronoUnit.DAYS.between(start, end) > 3650) {
      throw new ApiException(400, "INVALID_DATES", "Date range must not exceed 3650 days");
    }
  }
}
