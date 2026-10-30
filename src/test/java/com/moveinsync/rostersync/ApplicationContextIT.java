package com.moveinsync.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.support.IntegrationTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class ApplicationContextIT extends IntegrationTest {

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Test
	void startsAgainstARealPostgres16() {
		String version = jdbc.queryForObject("select current_setting('server_version')", String.class);

		assertThat(version).startsWith("16.");
	}

	@Test
	void reachesTheTestBroker() throws Exception {
		var sent = kafka.send("context-check", "key", "value").get(30, TimeUnit.SECONDS);

		assertThat(sent.getRecordMetadata().offset()).isGreaterThanOrEqualTo(0);
	}
}
