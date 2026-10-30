package com.moveinsync.rostersync.api.security;

import com.moveinsync.rostersync.domain.TenantId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller method declare a {@link TenantId} parameter and receive the tenant of
 * the authenticated token. There is no other way to obtain one in the API layer.
 */
public class TenantArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == TenantId.class;
	}

	@Override
	public TenantId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken token) {
			return TenantClaims.tenantOf(token.getToken());
		}
		throw new IllegalStateException("tenant requested on an endpoint that is not token-protected");
	}
}
