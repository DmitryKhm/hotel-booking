package com.example.hotel.booking.user;

import jakarta.validation.constraints.*;

public final class UserDtos {
  private UserDtos() {}

  public record RegisterRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 8, max = 72) String password) {}

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  public record CreateUserRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotNull UserAccount.Role role) {}

  public record UpdateUserRequest(
      @NotNull @Positive Long id,
      @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @Size(min = 8, max = 72) String password,
      UserAccount.Role role) {}

  public record UserView(long id, String username, UserAccount.Role role) {
    public static UserView from(UserAccount user) {
      return new UserView(user.getId(), user.getUsername(), user.getRole());
    }
  }

  public record TokenResponse(
      String accessToken, String tokenType, long expiresIn, UserView user) {}
}
