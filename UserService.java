package com.example.hotel.booking.user;

import com.example.hotel.booking.reservation.BookingRepository;
import com.example.hotel.common.ApiException;
import com.example.hotel.common.JwtTokens;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final BookingRepository bookings;
  private final PasswordEncoder encoder;
  private final JwtTokens tokens;

  public UserService(
      UserRepository users, BookingRepository bookings, PasswordEncoder encoder, JwtTokens tokens) {
    this.users = users;
    this.bookings = bookings;
    this.encoder = encoder;
    this.tokens = tokens;
  }

  @Transactional
  public UserDtos.TokenResponse register(UserDtos.RegisterRequest request) {
    UserAccount user = create(request.username(), request.password(), UserAccount.Role.USER);
    return token(user);
  }

  @Transactional(readOnly = true)
  public UserDtos.TokenResponse login(UserDtos.LoginRequest request) {
    UserAccount user =
        users
            .findByUsername(normalize(request.username()))
            .filter(found -> encoder.matches(request.password(), found.getPassword()))
            .orElseThrow(
                () ->
                    new ApiException(
                        401, "INVALID_CREDENTIALS", "Неверное имя пользователя или пароль"));
    return token(user);
  }

  @Transactional
  public UserDtos.UserView create(UserDtos.CreateUserRequest request) {
    return UserDtos.UserView.from(create(request.username(), request.password(), request.role()));
  }

  @Transactional
  public UserDtos.UserView update(UserDtos.UpdateUserRequest request) {
    UserAccount user = users.findLockedById(request.id()).orElseThrow(UserService::notFound);
    if (request.username() != null) {
      String username = normalize(request.username());
      if (!username.equals(user.getUsername()) && users.existsByUsername(username)) {
        throw new ApiException(409, "USERNAME_EXISTS", "Имя пользователя уже занято");
      }
      user.setUsername(username);
    }
    if (request.password() != null) user.setPassword(encode(request.password()));
    if (request.role() != null) user.setRole(request.role());
    return UserDtos.UserView.from(user);
  }

  @Transactional
  public void delete(long id) {
    UserAccount user = users.findLockedById(id).orElseThrow(UserService::notFound);
    if (bookings.existsByUserId(id)) {
      throw new ApiException(
          409, "USER_HAS_BOOKINGS", "Нельзя удалить пользователя с историей бронирований");
    }
    users.delete(user);
  }

  @Transactional(readOnly = true)
  public Page<UserDtos.UserView> list(Pageable pageable) {
    pageable
        .getSort()
        .forEach(
            order -> {
              if (!java.util.Set.of("id", "username", "role").contains(order.getProperty())) {
                throw new ApiException(
                    400, "INVALID_SORT", "Допустимая сортировка: id, username, role");
              }
            });
    return users.findAll(pageable).map(UserDtos.UserView::from);
  }

  @Transactional(readOnly = true)
  public UserDtos.UserView get(long id) {
    return UserDtos.UserView.from(users.findById(id).orElseThrow(UserService::notFound));
  }

  private UserAccount create(String rawUsername, String password, UserAccount.Role role) {
    String username = normalize(rawUsername);
    if (users.existsByUsername(username)) {
      throw new ApiException(409, "USERNAME_EXISTS", "Имя пользователя уже занято");
    }
    return users.saveAndFlush(new UserAccount(username, encode(password), role));
  }

  private String encode(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new ApiException(
          400, "PASSWORD_TOO_LONG", "Пароль BCrypt не может превышать 72 байта UTF-8");
    }
    return encoder.encode(password);
  }

  private UserDtos.TokenResponse token(UserAccount user) {
    return new UserDtos.TokenResponse(
        tokens.issueUser(user.getId(), user.getUsername(), user.getRole().name()),
        "Bearer",
        3600,
        UserDtos.UserView.from(user));
  }

  private static String normalize(String username) {
    return username.toLowerCase(Locale.ROOT);
  }

  private static ApiException notFound() {
    return new ApiException(404, "USER_NOT_FOUND", "Пользователь не найден");
  }
}
