package com.moveinsync.rostersync.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A throwaway Kafka-compatible broker for integration tests, same image as
 * docker-compose.yml. {@code @ServiceConnection} points spring.kafka at it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaContainerConfig {

	static final String IMAGE = "redpandadata/redpanda:v26.2.6";

	@Bean
	@ServiceConnection
	RedpandaContainer redpanda() {
		return new RedpandaContainer(IMAGE);
	}
}
