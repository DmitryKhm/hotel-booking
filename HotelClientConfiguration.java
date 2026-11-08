package com.example.hotel.booking.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HotelClientConfiguration {
  @Bean
  @LoadBalanced
  @ConditionalOnProperty(
      name = "hotel.client.discovery-enabled",
      havingValue = "true",
      matchIfMissing = true)
  RestClient.Builder hotelRestClientBuilder(
      @Value("${hotel.client.connect-timeout-ms:800}") long connectTimeoutMs,
      @Value("${hotel.client.read-timeout-ms:1500}") long readTimeoutMs) {
    return newBuilder(connectTimeoutMs, readTimeoutMs);
  }

  @Bean(name = "hotelRestClientBuilder")
  @ConditionalOnProperty(name = "hotel.client.discovery-enabled", havingValue = "false")
  RestClient.Builder directHotelRestClientBuilder(
      @Value("${hotel.client.connect-timeout-ms:800}") long connectTimeoutMs,
      @Value("${hotel.client.read-timeout-ms:1500}") long readTimeoutMs) {
    return newBuilder(connectTimeoutMs, readTimeoutMs);
  }

  private static RestClient.Builder newBuilder(long connectTimeoutMs, long readTimeoutMs) {
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    return RestClient.builder().requestFactory(factory);
  }
}
