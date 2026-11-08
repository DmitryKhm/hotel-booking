package com.example.hotel.gateway.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {
  @Bean
  RouteLocator applicationRoutes(
      RouteLocatorBuilder builder,
      @Value("${gateway.services.booking-uri:lb://booking-service}") String bookingUri,
      @Value("${gateway.services.hotel-uri:lb://hotel-service}") String hotelUri) {
    return builder
        .routes()
        .route(
            "booking-collection-read",
            route ->
                route
                    .method(HttpMethod.GET)
                    .and()
                    .path("/api/bookings")
                    .filters(filters -> filters.setPath("/bookings"))
                    .uri(bookingUri))
        .route(
            "booking-collection-create",
            route ->
                route
                    .method(HttpMethod.POST)
                    .and()
                    .path("/api/bookings")
                    .filters(filters -> filters.setPath("/booking"))
                    .uri(bookingUri))
        .route(
            "booking-item",
            route ->
                route
                    .method(HttpMethod.GET, HttpMethod.DELETE)
                    .and()
                    .path("/api/bookings/{id:[0-9]+}")
                    .filters(filters -> filters.setPath("/booking/{id}"))
                    .uri(bookingUri))
        .route(
            "booking-prefixed",
            route ->
                route
                    .path(
                        "/api/bookings/booking/**",
                        "/api/bookings/bookings",
                        "/api/bookings/user/**")
                    .filters(filters -> filters.stripPrefix(2))
                    .uri(bookingUri))
        .route(
            "booking-original",
            route -> route.path("/booking/**", "/bookings", "/user/**").uri(bookingUri))
        .route(
            "hotels-public-api",
            route ->
                route
                    .path("/api/hotels/**", "/api/rooms/**")
                    .and()
                    .predicate(exchange -> !InternalEndpoints.matches(exchange))
                    .uri(hotelUri))
        .build();
  }
}
