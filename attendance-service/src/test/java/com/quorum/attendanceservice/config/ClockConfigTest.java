package com.quorum.attendanceservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ClockConfig.class);

    @Test
    void defaultsToAsiaKolkata() {
        runner.run(ctx -> assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void zoneCanBeOverridden() {
        runner.withPropertyValues("quorum.time-zone=UTC")
                .run(ctx -> assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("UTC")));
    }
}
