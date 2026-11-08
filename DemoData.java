package com.example.hotel.booking.config;

import com.example.hotel.booking.user.UserAccount;
import com.example.hotel.booking.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoData implements CommandLineRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final String adminPassword;
  private final String userPassword;

  public DemoData(
      UserRepository users,
      PasswordEncoder encoder,
      @Value("${demo.admin-password:Admin123!}") String adminPassword,
      @Value("${demo.user-password:Demo123!}") String userPassword) {
    this.users = users;
    this.encoder = encoder;
    this.adminPassword = adminPassword;
    this.userPassword = userPassword;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (!users.existsByUsername("admin"))
      users.save(new UserAccount("admin", encoder.encode(adminPassword), UserAccount.Role.ADMIN));
    if (!users.existsByUsername("demo"))
      users.save(new UserAccount("demo", encoder.encode(userPassword), UserAccount.Role.USER));
  }
}
