package br.com.lcmtech.optics.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PublicRateLimitFilterTest {
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-13T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void bloqueiaDepoisDoLimiteELiberaNaJanelaSeguinte() {
        MutableClock clock = new MutableClock();
        PublicRateLimitFilter filter = new PublicRateLimitFilter(clock);
        for (int i = 0; i < 5; i++) assertThat(filter.allow("visit", "1.2.3.4", 5, Duration.ofHours(1))).isTrue();
        assertThat(filter.allow("visit", "1.2.3.4", 5, Duration.ofHours(1))).isFalse();
        // Outro IP e outro formulário não são afetados.
        assertThat(filter.allow("visit", "5.6.7.8", 5, Duration.ofHours(1))).isTrue();
        assertThat(filter.allow("signup", "1.2.3.4", 5, Duration.ofHours(1))).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertThat(filter.allow("visit", "1.2.3.4", 5, Duration.ofHours(1))).isTrue();
    }
}
