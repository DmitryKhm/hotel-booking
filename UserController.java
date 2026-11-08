package com.example.hotel.booking.user;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@Validated
public class UserController {
  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @SecurityRequirements
  public UserDtos.TokenResponse register(@Valid @RequestBody UserDtos.RegisterRequest request) {
    return service.register(request);
  }

  @PostMapping("/auth")
  @SecurityRequirements
  public UserDtos.TokenResponse authenticate(@Valid @RequestBody UserDtos.LoginRequest request) {
    return service.login(request);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public UserDtos.UserView create(@Valid @RequestBody UserDtos.CreateUserRequest request) {
    return service.create(request);
  }

  @PatchMapping
  @PreAuthorize("hasRole('ADMIN')")
  public UserDtos.UserView update(@Valid @RequestBody UserDtos.UpdateUserRequest request) {
    return service.update(request);
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public Page<UserDtos.UserView> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
    return service.list(pageable);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public UserDtos.UserView get(@PathVariable @Positive long id) {
    return service.get(id);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@RequestParam @Positive long id) {
    service.delete(id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteById(@PathVariable @Positive long id) {
    service.delete(id);
  }
}
