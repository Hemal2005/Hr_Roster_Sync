package com.moveinsync.rostersync.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that need the full application against a real Postgres. Spring caches
 * the context between test classes with the same configuration, so the container
 * starts once per run.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
public abstract class IntegrationTest {
}
