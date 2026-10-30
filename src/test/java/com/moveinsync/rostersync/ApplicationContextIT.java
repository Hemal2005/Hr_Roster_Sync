package com.moveinsync.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class ApplicationContextIT extends IntegrationTest {

	@Test
	void startsAgainstARealPostgres16() {
		String version = jdbc.queryForObject("select current_setting('server_version')", String.class);

		assertThat(version).startsWith("16.");
	}
}
