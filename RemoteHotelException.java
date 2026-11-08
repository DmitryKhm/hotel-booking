package com.example.hotel.booking.client;

public class RemoteHotelException extends RuntimeException {
  private final int status;
  private final boolean retryable;

  public RemoteHotelException(int status, String message, boolean retryable) {
    super(message);
    this.status = status;
    this.retryable = retryable;
  }

  public RemoteHotelException(int status, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.retryable = retryable;
  }

  public int status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }
}
