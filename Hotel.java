package com.example.hotel.hotel.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "hotels")
public class Hotel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(nullable = false, length = 300)
  private String address;

  @Column(nullable = false)
  private boolean deleted;

  @Version private long version;

  protected Hotel() {}

  public Hotel(String name, String address) {
    this.name = name;
    this.address = address;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getAddress() {
    return address;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public void update(String name, String address) {
    this.name = name;
    this.address = address;
  }

  public void delete() {
    this.deleted = true;
  }
}
