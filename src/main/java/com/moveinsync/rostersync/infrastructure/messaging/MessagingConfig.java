package com.moveinsync.rostersync.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
}
