package com.moveinsync.rostersync.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
class MessagingConfig {

	/**
	 * Created at startup if missing. Events are keyed by tenant and employee, so each
	 * employee's events land on one partition, in order; partitions let consumers scale out.
	 */
	@Bean
	NewTopic employeeChangesTopic(@Value("${rostersync.outbox.topic}") String topic,
			@Value("${rostersync.outbox.partitions:6}") int partitions) {
		return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
	}

	/**
	 * A failing side effect (database down) is retried with growing pauses for up to ten
	 * minutes before the record is given up and logged, instead of the default of a few
	 * immediate retries.
	 */
	@Bean
	CommonErrorHandler consumerErrorHandler() {
		ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
		backOff.setMaxInterval(30_000);
		backOff.setMaxElapsedTime(600_000);
		return new DefaultErrorHandler(backOff);
	}
}
