package com.quorum.timetableservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the outbox relay. Tests switch it off with quorum.outbox.relay.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "quorum.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
