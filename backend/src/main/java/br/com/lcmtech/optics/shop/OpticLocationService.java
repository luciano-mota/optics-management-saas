package br.com.lcmtech.optics.shop;

import br.com.lcmtech.optics.shop.domain.OpticLocation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava o endereço da ótica e, a partir dele, a localização do calendário
 * (UF, município IBGE e fuso). O usuário nunca digita código IBGE nem fuso.
 */
@Service
public class OpticLocationService {
    private final JdbcTemplate jdbc;

    public OpticLocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean hasAddress(UUID optic) {
        String address = jdbc.query("SELECT coalesce(address,'') FROM optics WHERE id=?",
                (rs, n) -> rs.getString(1), optic).stream().findFirst().orElse("");
        return !address.isBlank();
    }

    @Transactional
    public void save(UUID optic, UUID actor, OpticLocation location) {
        jdbc.update("UPDATE optics SET address=?,updated_at=now() WHERE id=?", location.address(), optic);
        // Dias de abertura já configurados são preservados; só a localização muda.
        jdbc.update("""
            INSERT INTO optic_business_calendar
                (optic_id,state_uf,municipality_ibge,municipality_name,time_zone,updated_by)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(optic_id) DO UPDATE SET
                state_uf=excluded.state_uf,municipality_ibge=excluded.municipality_ibge,
                municipality_name=excluded.municipality_name,time_zone=excluded.time_zone,
                updated_by=excluded.updated_by,updated_at=now()
            """, optic, location.state().name(), location.municipalityIbge(), location.city(),
                location.zone().getId(), actor);
        jdbc.update("""
            INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,?,'OPTIC_ADDRESS_UPDATED','OPTIC',?)
            """, UUID.randomUUID(), optic, actor, optic);
    }

    public Map<String, Object> describe(UUID optic) {
        Map<String, Object> result = new HashMap<>(jdbc.queryForMap(
                "SELECT trade_name,coalesce(address,'') AS address FROM optics WHERE id=?", optic));
        jdbc.queryForList("""
            SELECT state_uf,municipality_name,municipality_ibge,time_zone
            FROM optic_business_calendar WHERE optic_id=?
            """, optic).stream().findFirst().ifPresent(result::putAll);
        result.putIfAbsent("state_uf", null);
        result.putIfAbsent("municipality_name", null);
        result.putIfAbsent("municipality_ibge", null);
        return result;
    }
}
