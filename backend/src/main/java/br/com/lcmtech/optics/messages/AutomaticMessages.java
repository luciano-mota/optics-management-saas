package br.com.lcmtech.optics.messages;

import br.com.lcmtech.optics.identity.EmailDelivery;
import br.com.lcmtech.optics.shop.OpticClock;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Gera e envia os avisos automáticos por e-mail: lembrete de consulta no dia anterior e parabéns no aniversário.
 * Só para quem autorizou, cada aviso uma única vez (dedupe), com até 5 tentativas e link de descadastro.
 * Antes de enviar confere de novo: consulta cancelada, aviso desligado ou autorização retirada cancelam o envio.
 */
@Service
public class AutomaticMessages {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticMessages.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final OpticClock clock;
    private final EmailDelivery mail;
    private final boolean enabled;
    private final String baseUrl;

    public AutomaticMessages(JdbcTemplate jdbc, OpticClock clock, EmailDelivery mail,
                             @Value("${optics.messages.scheduler-enabled:true}") boolean enabled,
                             @Value("${optics.public-base-url:https://optics-management-hml.onrender.com}") String baseUrl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.mail = mail;
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Scheduled(initialDelayString = "${optics.messages.initial-delay-ms:120000}",
            fixedDelayString = "${optics.messages.interval-ms:600000}")
    public void run() {
        if (!enabled) return;
        try {
            Instant now = Instant.now();
            enqueue(now);
            deliver(now);
        } catch (RuntimeException failure) {
            LOG.warn("Falha na rotina de avisos automáticos: {}", failure.getClass().getSimpleName());
        }
    }

    /** Coloca na fila os avisos do dia de cada ótica (no fuso dela). Rodar de novo não duplica. */
    public int enqueue(Instant now) {
        int created = 0;
        List<Map<String, Object>> optics = jdbc.queryForList("""
                SELECT ms.optic_id, ms.appointment_reminder_enabled, ms.birthday_email_enabled, ms.appointment_template,
                       ms.birthday_template, ms.version, o.trade_name, coalesce(o.contact_phone,'') AS phone
                FROM optic_message_settings ms JOIN optics o ON o.id=ms.optic_id
                WHERE o.active AND (ms.appointment_reminder_enabled OR ms.birthday_email_enabled)""");
        for (Map<String, Object> o : optics) {
            UUID optic = (UUID) o.get("optic_id");
            ZoneId zone = clock.zone(optic);
            LocalDate today = now.atZone(zone).toLocalDate();
            String shop = (String) o.get("trade_name"), phone = phoneMask((String) o.get("phone"));
            int version = ((Number) o.get("version")).intValue();
            if (Boolean.TRUE.equals(o.get("appointment_reminder_enabled"))) {
                LocalDate tomorrow = today.plusDays(1);
                Instant from = tomorrow.atStartOfDay(zone).toInstant(), to = tomorrow.plusDays(1).atStartOfDay(zone).toInstant();
                for (Map<String, Object> a : jdbc.queryForList("""
                        SELECT a.id, a.starts_at, p.full_name, p.email, k.name AS clinic
                        FROM optical_appointments a
                        JOIN optometry_patients p ON p.optic_id=a.optic_id AND p.id=a.patient_id
                        JOIN optical_partner_clinics k ON k.optic_id=a.optic_id AND k.id=a.clinic_id
                        WHERE a.optic_id=? AND a.starts_at>=? AND a.starts_at<? AND a.status IN ('SCHEDULED','CONFIRMED')
                          AND p.reminder_opt_in AND p.email IS NOT NULL""",
                        optic, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to))) {
                    var start = ((java.sql.Timestamp) a.get("starts_at")).toInstant().atZone(zone);
                    String body = MessageTemplates.render((String) o.get("appointment_template"), Map.of(
                            "nome", MessageTemplates.firstName((String) a.get("full_name")), "otica", shop,
                            "data", start.format(DAY), "hora", start.format(HOUR), "clinica", (String) a.get("clinic"),
                            "telefone", phone));
                    created += insert(optic, "APPOINTMENT_REMINDER", (UUID) a.get("id"), tomorrow.toString(),
                            (String) a.get("email"), "Lembrete da sua consulta amanhã · " + shop, body, version);
                }
            }
            if (Boolean.TRUE.equals(o.get("birthday_email_enabled"))) {
                boolean feb28NonLeap = today.getMonthValue() == 2 && today.getDayOfMonth() == 28 && !today.isLeapYear();
                for (Map<String, Object> c : jdbc.queryForList("""
                        SELECT id, full_name, email FROM customers
                        WHERE optic_id=? AND email_opt_in AND email IS NOT NULL AND birth_date IS NOT NULL
                          AND extract(month FROM birth_date)=? AND (extract(day FROM birth_date)=?
                               OR (? AND extract(month FROM birth_date)=2 AND extract(day FROM birth_date)=29))""",
                        optic, today.getMonthValue(), today.getDayOfMonth(), feb28NonLeap)) {
                    String first = MessageTemplates.firstName((String) c.get("full_name"));
                    String body = MessageTemplates.render((String) o.get("birthday_template"),
                            Map.of("nome", first, "otica", shop, "telefone", phone));
                    created += insert(optic, "BIRTHDAY", (UUID) c.get("id"), String.valueOf(today.getYear()),
                            (String) c.get("email"), "Feliz aniversário, " + first + "!", body, version);
                }
            }
        }
        return created;
    }

    private int insert(UUID optic, String kind, UUID ref, String dedupe, String email, String subject, String body, int version) {
        return jdbc.update("""
                INSERT INTO message_outbox(optic_id,id,kind,ref_id,dedupe_key,recipient_email,subject,body,template_version,
                    unsubscribe_hash) VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (optic_id,kind,ref_id,dedupe_key) DO NOTHING""",
                optic, UUID.randomUUID(), kind, ref, dedupe, email.trim(), subject, body, version, hash(token()));
    }

    /** Envia o que está na fila e venceu. Devolve quantos saíram. */
    public int deliver(Instant now) {
        int sent = 0;
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT m.optic_id, m.id, m.kind, m.ref_id, m.recipient_email, m.subject, m.body, m.attempts, o.trade_name
                FROM message_outbox m JOIN optics o ON o.id=m.optic_id
                WHERE m.status='PENDING' AND m.next_attempt_at<=? ORDER BY m.created_at LIMIT 50""",
                java.sql.Timestamp.from(now))) {
            UUID optic = (UUID) m.get("optic_id"), id = (UUID) m.get("id");
            String reason = stillValid(optic, (String) m.get("kind"), (UUID) m.get("ref_id"), now);
            if (reason != null) {
                jdbc.update("UPDATE message_outbox SET status='CANCELLED', last_error=? WHERE optic_id=? AND id=?", reason, optic, id);
                continue;
            }
            if (!mail.available()) {
                jdbc.update("UPDATE message_outbox SET status='FAILED', last_error='Envio de e-mail não configurado no servidor.' "
                        + "WHERE optic_id=? AND id=?", optic, id);
                continue;
            }
            // Link de descadastro novo a cada tentativa; só o hash fica guardado.
            String token = token();
            jdbc.update("UPDATE message_outbox SET unsubscribe_hash=? WHERE optic_id=? AND id=?", hash(token), optic, id);
            String body = m.get("body") + "\n\n--\nVocê recebe este e-mail porque autorizou avisos da " + m.get("trade_name")
                    + ".\nPara não receber mais: " + baseUrl + "/api/v1/public/messages/unsubscribe/" + token;
            int attempts = ((Number) m.get("attempts")).intValue() + 1;
            try {
                mail.send((String) m.get("recipient_email"), (String) m.get("subject"), body);
                jdbc.update("UPDATE message_outbox SET status='SENT', attempts=?, sent_at=now(), last_error=NULL "
                        + "WHERE optic_id=? AND id=?", attempts, optic, id);
                sent++;
            } catch (RuntimeException failure) {
                boolean last = attempts >= MAX_ATTEMPTS;
                jdbc.update("UPDATE message_outbox SET status=?, attempts=?, next_attempt_at=?, last_error=? WHERE optic_id=? AND id=?",
                        last ? "FAILED" : "PENDING", attempts,
                        java.sql.Timestamp.from(now.plus(Duration.ofMinutes(10L << Math.min(attempts, 6)))),
                        "Falha no envio (tentativa " + attempts + " de " + MAX_ATTEMPTS + ").", optic, id);
            }
        }
        return sent;
    }

    private String stillValid(UUID optic, String kind, UUID ref, Instant now) {
        if ("APPOINTMENT_REMINDER".equals(kind)) {
            Integer ok = jdbc.queryForObject("""
                    SELECT count(*) FROM optical_appointments a
                    JOIN optometry_patients p ON p.optic_id=a.optic_id AND p.id=a.patient_id
                    JOIN optic_message_settings ms ON ms.optic_id=a.optic_id AND ms.appointment_reminder_enabled
                    WHERE a.optic_id=? AND a.id=? AND a.status IN ('SCHEDULED','CONFIRMED') AND a.starts_at>?
                      AND p.reminder_opt_in""", Integer.class, optic, ref, java.sql.Timestamp.from(now));
            return ok != null && ok > 0 ? null : "Consulta cancelada, já passou, aviso desligado ou autorização retirada.";
        }
        Integer ok = jdbc.queryForObject("""
                SELECT count(*) FROM customers c JOIN optic_message_settings ms ON ms.optic_id=c.optic_id AND ms.birthday_email_enabled
                WHERE c.optic_id=? AND c.id=? AND c.email_opt_in""", Integer.class, optic, ref);
        return ok != null && ok > 0 ? null : "Aviso desligado ou autorização retirada.";
    }

    /** Descadastro pelo link do e-mail: tira a autorização do cliente ou paciente. Devolve o nome da ótica. */
    public String unsubscribe(String token) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT m.optic_id, m.kind, m.ref_id, o.trade_name FROM message_outbox m JOIN optics o ON o.id=m.optic_id
                WHERE m.unsubscribe_hash=?""", hash(token));
        if (found.isEmpty()) return null;
        Map<String, Object> m = found.get(0);
        if ("BIRTHDAY".equals(m.get("kind")))
            jdbc.update("UPDATE customers SET email_opt_in=false, email_opt_in_at=NULL, updated_at=now() WHERE optic_id=? AND id=?",
                    m.get("optic_id"), m.get("ref_id"));
        else
            jdbc.update("""
                    UPDATE optometry_patients SET reminder_opt_in=false, reminder_opt_in_at=NULL
                    WHERE optic_id=? AND id=(SELECT patient_id FROM optical_appointments WHERE optic_id=? AND id=?)""",
                    m.get("optic_id"), m.get("optic_id"), m.get("ref_id"));
        return (String) m.get("trade_name");
    }

    private static String token() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String phoneMask(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.length() == 11) return "(" + d.substring(0, 2) + ") " + d.substring(2, 7) + "-" + d.substring(7);
        if (d.length() == 10) return "(" + d.substring(0, 2) + ") " + d.substring(2, 6) + "-" + d.substring(6);
        return d.isEmpty() ? "da loja" : d;
    }
}
