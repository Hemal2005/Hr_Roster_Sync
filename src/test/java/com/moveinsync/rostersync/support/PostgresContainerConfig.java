package com.moveinsync.rostersync.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway Postgres for integration tests, same image as docker-compose.yml.
 * {@code @ServiceConnection} points the datasource at it, so tests never touch the
 * local compose database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

	static final String IMAGE = "postgres:16.15";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(IMAGE);
	}
}
