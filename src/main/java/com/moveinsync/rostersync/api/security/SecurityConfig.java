package com.moveinsync.rostersync.api.security;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Who may call what. Every endpoint is listed with its role; anything not listed is
 * denied, so a new endpoint is closed until someone decides who may use it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSettings.class)
class SecurityConfig implements WebMvcConfigurer {

	static final String ROLES_CLAIM = "roles";

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper json) throws Exception {
		http
				// Bearer tokens in a header, no cookies or sessions: CSRF cannot apply.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(HttpMethod.POST, "/api/v1/hr/events:batch").hasRole(Role.HR_INTEGRATION.name())
						.requestMatchers(HttpMethod.GET, "/api/v1/employees/me").hasRole(Role.EMPLOYEE.name())
						.requestMatchers(HttpMethod.GET, "/api/v1/employees/*").hasRole(Role.ADMIN.name())
						.requestMatchers(HttpMethod.PATCH, "/api/v1/employees/*/transport").hasRole(Role.ADMIN.name())
						.requestMatchers(HttpMethod.POST, "/api/v1/bookings").hasRole(Role.ADMIN.name())
						.requestMatchers(HttpMethod.GET, "/api/v1/bookings").hasRole(Role.ADMIN.name())
						// Only mapped under the dev and demo profiles; elsewhere it is a 404.
						.requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
						.requestMatchers("/error").permitAll()
						.anyRequest().denyAll())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromClaim()))
						.authenticationEntryPoint(ProblemResponses.unauthenticated(json))
						.accessDeniedHandler(ProblemResponses.forbidden(json)))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(ProblemResponses.unauthenticated(json))
						.accessDeniedHandler(ProblemResponses.forbidden(json)));
		return http.build();
	}

	/** {@code "roles": ["ADMIN"]} becomes the authority ROLE_ADMIN, which hasRole("ADMIN") checks. */
	private static JwtAuthenticationConverter rolesFromClaim() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(ROLES_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	/**
	 * Verifies the HS256 signature, expiry and not-before, and refuses a token that does
	 * not name a tenant.
	 */
	@Bean
	JwtDecoder jwtDecoder(JwtSettings settings) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(settings.key())
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(),
				new JwtClaimValidator<String>(TenantClaims.TENANT_ID, tenant -> tenant != null && !tenant.isBlank())));
		return decoder;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new TenantArgumentResolver());
	}
}
