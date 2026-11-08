package com.example.hotel.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.discovery.EurekaClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "eureka.server.wait-time-in-ms-when-sync-empty=0")
class DiscoveryApplicationTest {
  @Autowired private EurekaClientConfig clientConfig;

  @Test
  void standaloneRegistryDoesNotRegisterItselfOrFetchOtherRegistries() {
    assertThat(clientConfig.shouldRegisterWithEureka()).isFalse();
    assertThat(clientConfig.shouldFetchRegistry()).isFalse();
  }
}
