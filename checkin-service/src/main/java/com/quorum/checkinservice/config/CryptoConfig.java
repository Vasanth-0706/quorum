package com.quorum.checkinservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

@Configuration
public class CryptoConfig {

    /** Hashes device API keys. Only spring-security-crypto is on the classpath, no filter chain. */
    @Bean
    public PasswordEncoder deviceKeyEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
