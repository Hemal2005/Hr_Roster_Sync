package com.moveinsync.rostersync.infrastructure.persistence;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

	/**
	 * Changing the bucket count after data exists requires recomputing employee.bucket.
	 */
	@Bean
	BucketAssigner bucketAssigner(
			@Value("${rostersync.reconciliation.bucket-count:" + BucketAssigner.DEFAULT_BUCKET_COUNT + "}") int bucketCount) {
		return new BucketAssigner(bucketCount);
	}

	@Bean
	CachingOfficeDirectory officeDirectory(JdbcTemplate jdbc, MeterRegistry meters) {
		CachingOfficeDirectory offices = new CachingOfficeDirectory(new JdbcOfficeDirectory(jdbc), Ticker.systemTicker());
		CaffeineCacheMetrics.monitor(meters, offices.cache(), "offices");
		return offices;
	}
}
