package com.quorum.placementservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime NOON = LocalTime.of(12, 0);

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Map<String, String> errors(Object bean) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        return violations.stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage, (a, b) -> a + "|" + b));
    }

    @Test
    void validDriveHasNoErrors() {
        assertThat(errors(new CreateDriveRequest("Acme Corp", DAY, NINE, NOON, "Main Hall"))).isEmpty();
    }

    @Test
    void driveRequiresCompanyVenueAndDate() {
        Map<String, String> e = errors(new CreateDriveRequest("  ", null, NINE, NOON, ""));
        assertThat(e).containsOnlyKeys("companyName", "venue", "driveDate");
    }

    @Test
    void driveRequiresBothTimes() {
        assertThat(errors(new CreateDriveRequest("Acme", DAY, null, null, "Hall")))
                .containsOnlyKeys("startTime", "endTime");
    }

    @Test
    void driveEndMustBeAfterStart() {
        assertThat(errors(new CreateDriveRequest("Acme", DAY, NOON, NINE, "Hall")))
                .containsExactly(Map.entry("endTime", "must be after startTime"));
        assertThat(errors(new CreateDriveRequest("Acme", DAY, NINE, NINE, "Hall")))
                .containsOnlyKeys("endTime");
    }

    @Test
    void driveRejectsOverlongCompanyName() {
        assertThat(errors(new CreateDriveRequest("x".repeat(121), DAY, NINE, NOON, "Hall")))
                .containsOnlyKeys("companyName");
    }

    @Test
    void roundNeedsNameAndPositiveSequence() {
        assertThat(errors(new CreateRoundRequest("Technical", 2))).isEmpty();
        assertThat(errors(new CreateRoundRequest(" ", null))).containsOnlyKeys("name", "sequence");
        assertThat(errors(new CreateRoundRequest("HR", 0))).containsOnlyKeys("sequence");
    }

    @Test
    void sessionRules() {
        assertThat(errors(new SessionRequest(DAY, NINE, NOON, "Lab 1"))).isEmpty();
        assertThat(errors(new SessionRequest(null, null, null, ""))).containsOnlyKeys("date", "startTime", "endTime", "room");
        assertThat(errors(new SessionRequest(DAY, NOON, NINE, "Lab 1"))).containsOnlyKeys("endTime");
    }
}
