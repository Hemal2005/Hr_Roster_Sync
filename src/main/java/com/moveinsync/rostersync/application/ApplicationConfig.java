package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.validation.CompositeValidator;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApplicationConfig {

	@Bean
	RecordValidator recordValidator(OfficeDirectory offices) {
		return CompositeValidator.standard(offices);
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
