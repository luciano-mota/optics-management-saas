package br.com.lcmtech.optics.optometry;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agenda da clínica parceira em formato de calendário (iCal), para ela assinar no Google Agenda ou no Outlook.
 * Mostra só as consultas daquela clínica, com o primeiro nome e a inicial do paciente (sem telefone, CPF ou e-mail).
 * O link pode ser trocado ou revogado a qualquer momento; só o hash do token fica guardado.
 */
@RestController
public class ClinicCalendarController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ICS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final String baseUrl;

    public ClinicCalendarController(JdbcTemplate jdbc, OpticContext context,
                                    @Value("${optics.public-base-url:https://optics-management-hml.onrender.com}") String baseUrl) {
        this.jdbc = jdbc;
        this.context = context;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    private void requireClinic(UUID optic, UUID clinic) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM optical_partner_clinics WHERE optic_id=? AND id=?",
                Integer.class, optic, clinic);
        if (n == null || n == 0) throw new NoSuchElementException("Clínica não encontrada.");
    }

    @GetMapping("/api/v1/optometry/clinics/{clinicId}/calendar-feed")
    public Map<String, Object> status(@PathVariable UUID clinicId, Authentication auth, HttpSession session) {
        context.require(auth, session, "PARTNER_CLINICS_MANAGE");
        UUID optic = context.optic(auth, session);
        requireClinic(optic, clinicId);
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT created_at, last_access_at FROM clinic_calendar_feeds
                WHERE optic_id=? AND clinic_id=? AND revoked_at IS NULL""", optic, clinicId);
        return active.isEmpty() ? Map.of("active", false)
                : Map.of("active", true, "createdAt", String.valueOf(active.get(0).get("created_at")),
                "lastAccessAt", String.valueOf(active.get(0).get("last_access_at")));
    }

    /** Gera um link novo (o anterior para de funcionar). O link só aparece agora; depois, só gerando outro. */
    @PostMapping("/api/v1/optometry/clinics/{clinicId}/calendar-feed")
    @Transactional
    public Map<String, Object> create(@PathVariable UUID clinicId, Authentication auth, HttpSession session) {
        context.require(auth, session, "PARTNER_CLINICS_MANAGE");
        UUID optic = context.optic(auth, session);
        requireClinic(optic, clinicId);
        jdbc.update("UPDATE clinic_calendar_feeds SET revoked_at=now() WHERE optic_id=? AND clinic_id=? AND revoked_at IS NULL",
                optic, clinicId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO clinic_calendar_feeds(optic_id,id,clinic_id,token_hash,created_by) VALUES(?,?,?,?,?)",
                optic, UUID.randomUUID(), clinicId, hash(token), context.accountId(auth));
        String url = baseUrl + "/api/v1/public/calendar/" + token + "/agenda.ics";
        return Map.of("url", url, "webcal", url.replaceFirst("^https?://", "webcal://"));
    }

    @PostMapping("/api/v1/optometry/clinics/{clinicId}/calendar-feed/revoke")
    public Map<String, Boolean> revoke(@PathVariable UUID clinicId, Authentication auth, HttpSession session) {
        context.require(auth, session, "PARTNER_CLINICS_MANAGE");
        UUID optic = context.optic(auth, session);
        requireClinic(optic, clinicId);
        int n = jdbc.update("UPDATE clinic_calendar_feeds SET revoked_at=now() WHERE optic_id=? AND clinic_id=? AND revoked_at IS NULL",
                optic, clinicId);
        return Map.of("revoked", n > 0);
    }

    /** O calendário em si (público, protegido pelo token). Consultas de 30 dias atrás até 120 dias à frente. */
    @GetMapping("/api/v1/public/calendar/{token}/agenda.ics")
    public ResponseEntity<String> feed(@PathVariable String token) {
        List<Map<String, Object>> feeds = token.length() > 100 ? List.of() : jdbc.queryForList("""
                SELECT f.optic_id, f.clinic_id, f.id, k.name AS clinic, o.trade_name
                FROM clinic_calendar_feeds f
                JOIN optical_partner_clinics k ON k.optic_id=f.optic_id AND k.id=f.clinic_id
                JOIN optics o ON o.id=f.optic_id
                WHERE f.token_hash=? AND f.revoked_at IS NULL AND o.active""", hash(token));
        if (feeds.isEmpty()) return ResponseEntity.status(404).header("Content-Type", "text/plain;charset=UTF-8")
                .body("Agenda não encontrada ou link revogado.");
        Map<String, Object> f = feeds.get(0);
        jdbc.update("UPDATE clinic_calendar_feeds SET last_access_at=now() WHERE optic_id=? AND id=?", f.get("optic_id"), f.get("id"));
        Instant now = Instant.now();
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT a.id, a.starts_at, a.ends_at, a.status, a.updated_at, p.full_name
                FROM optical_appointments a JOIN optometry_patients p ON p.optic_id=a.optic_id AND p.id=a.patient_id
                WHERE a.optic_id=? AND a.clinic_id=? AND a.status<>'CANCELLED'
                  AND a.starts_at>=? AND a.starts_at<? ORDER BY a.starts_at""",
                f.get("optic_id"), f.get("clinic_id"), java.sql.Timestamp.from(now.minusSeconds(30L * 86400)),
                java.sql.Timestamp.from(now.plusSeconds(120L * 86400)));
        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//LCM DEV TECH//Optics Management//PT-BR");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:" + text("Consultas - " + f.get("trade_name")));
        line(ics, "X-PUBLISHED-TTL:PT1H");
        for (Map<String, Object> e : events) {
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:" + e.get("id") + "@optics-management");
            line(ics, "DTSTAMP:" + ICS.format(((java.sql.Timestamp) e.get("updated_at")).toInstant()));
            line(ics, "DTSTART:" + ICS.format(((java.sql.Timestamp) e.get("starts_at")).toInstant()));
            line(ics, "DTEND:" + ICS.format(((java.sql.Timestamp) e.get("ends_at")).toInstant()));
            line(ics, "SUMMARY:" + text("Consulta - " + shortName((String) e.get("full_name"))));
            line(ics, "DESCRIPTION:" + text("Paciente encaminhado pela " + f.get("trade_name") + ". Situação: "
                    + status((String) e.get("status")) + "."));
            line(ics, "STATUS:" + ("CONFIRMED".equals(e.get("status")) || "COMPLETED".equals(e.get("status")) ? "CONFIRMED" : "TENTATIVE"));
            line(ics, "END:VEVENT");
        }
        line(ics, "END:VCALENDAR");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Type", "text/calendar;charset=UTF-8")
                .header("X-Robots-Tag", "noindex").body(ics.toString());
    }

    /** "Maria Souza" vira "Maria S.": suficiente para a clínica reconhecer, sem expor o nome completo. */
    static String shortName(String fullName) {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "Paciente";
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    private static String status(String s) {
        return switch (s) {
            case "CONFIRMED" -> "confirmada";
            case "COMPLETED" -> "realizada";
            case "NO_SHOW" -> "não compareceu";
            default -> "agendada";
        };
    }

    /** Escapa texto do iCal (RFC 5545 §3.3.11). */
    static String text(String v) {
        return v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n").replace("\r", "");
    }

    /** Linhas com CRLF e dobradas em 75 bytes (RFC 5545 §3.1). */
    static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) { out.append(content).append("\r\n"); return; }
        StringBuilder current = new StringBuilder();
        int size = 0, limit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (size + len > limit) {
                out.append(current).append("\r\n ");
                current.setLength(0);
                size = 1;
                limit = 75;
            }
            current.append(ch);
            size += len;
            i += Character.charCount(cp);
        }
        out.append(current).append("\r\n");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
