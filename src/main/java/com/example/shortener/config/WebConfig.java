package com.example.shortener.config;

import com.example.shortener.security.ApiKeyInterceptor;
import com.example.shortener.security.RateLimitInterceptor;
import com.example.shortener.security.RateLimitPolicy;
import com.example.shortener.security.RateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Cross-cutting HTTP concerns as interceptors. They run inside the DispatcherServlet, so their exceptions
/// (`429`, `401`) go through the same global exception handler and share the uniform error format.
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiter rateLimiter;
    private final ApiKeyInterceptor apiKeyInterceptor;

    public WebConfig(RateLimiter rateLimiter, ApiKeyInterceptor apiKeyInterceptor) {
        this.rateLimiter = rateLimiter;
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. Authentication first: endpoints marked @RequiresApiKey get 401/403 here, and the client's name is stored
        //    on the request. Unauthenticated attempts only spend the per-IP auth-failure budget.
        registry.addInterceptor(apiKeyInterceptor).order(1);
        // 2. Creating links: strict, per API client (the request is authenticated by now).
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter, RateLimitPolicy.CREATE))
                .addPathPatterns("/api/v1/links")
                .order(2);
        // 3. Following links: lenient, per client IP. "/*" = single-segment paths, i.e. the short codes.
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter, RateLimitPolicy.REDIRECT))
                .addPathPatterns("/*")
                .excludePathPatterns("/error", "/swagger-ui.html", "/favicon.ico")
                .order(2);
    }
}
