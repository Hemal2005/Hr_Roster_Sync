package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
