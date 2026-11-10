package br.com.lcmtech.optics.shop;

import br.com.lcmtech.optics.shop.domain.BrazilianState;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * "Hoje" na ótica. O servidor roda em UTC: sem isto, uma venda às 22h no Brasil
 * ficaria com a data do dia seguinte e as contagens de dia/semana/mês mudariam às 21h.
 */
@Service
public class OpticClock {
    private final JdbcTemplate jdbc;

    public OpticClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ZoneId zone(UUID optic) {
        String stored = jdbc.query("SELECT coalesce(time_zone,'') FROM optic_business_calendar WHERE optic_id=?",
                (rs, n) -> rs.getString(1), optic).stream().findFirst().orElse(null);
        if (stored == null || stored.isBlank()) return BrazilianState.DEFAULT_ZONE;
        try {
            return ZoneId.of(stored);
        } catch (DateTimeException invalid) {
            return BrazilianState.DEFAULT_ZONE;
        }
    }

    public LocalDate today(UUID optic) {
        return LocalDate.now(zone(optic));
    }
}
