package com.fintrack.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Pins the business calendar, so a test about "this month" passes or fails on its merits
 * rather than on the day it runs.
 *
 * <h2>Why 28 August 2026</h2>
 * Late in a month, so "day N of this month" is in the past for every N up to 28 and
 * {@code @PastOrPresent} accepts it - the suite seeds up to day 18. And after every absolute
 * date the suite writes as a transaction, the latest being 23 August 2026.
 *
 * <p>Only business dates follow this clock. Tokens and audit timestamps stay on real time;
 * see {@link com.fintrack.api.config.ClockConfig}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    /** Primary, so it wins over the application's system clock wherever a Clock is injected. */
    @Bean
    @Primary
    Clock fixedClock() {
        return CLOCK;
    }
}
