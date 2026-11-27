package com.quorum.checkinservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** The reader endpoint, authenticated by X-Device-Key instead of the X-User headers. */
    public static final String TAP_PATH = "/checkins/tap";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TemporaryAuthInterceptor()).excludePathPatterns(TAP_PATH);
    }
}
