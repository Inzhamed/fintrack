package com.fintrack.api.config;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The business calendar: what "today" and "this month" mean to the application.
 *
 * <h2>Why a bean and not {@code LocalDate.now()}</h2>
 * A date read straight from the system clock cannot be controlled by a test, so any test
 * about "this month" silently depends on the day it happens to run. The suite proved it:
 * tests that seeded transactions on day 18 of the current month passed in late August and
 * failed in early October, when day 18 was still in the future and validation refused it.
 * Every business date now comes from this clock, and the tests replace it with a fixed one.
 *
 * <h2>What deliberately stays on the system clock</h2>
 * Token expiry and audit timestamps keep using {@code Instant.now()}. The JWT parser checks
 * expiry against real time, and the database stamps rows with real time, so pinning those
 * in a test would make its tokens expire - or never expire - out of step with everything
 * that validates them.
 */
@Configuration
public class ClockConfig {

    /** The JVM's zone, which is exactly what the {@code LocalDate.now()} calls it replaced used. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * {@code @PastOrPresent} must agree with the application on what "now" is. Without this
     * the validator keeps its own system clock, and a test with a pinned date would still
     * have its requests judged against the real one.
     */
    @Bean
    public ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
