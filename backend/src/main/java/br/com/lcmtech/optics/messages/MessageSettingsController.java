package br.com.lcmtech.optics.messages;

import br.com.lcmtech.optics.security.OpticContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Avisos automáticos da ótica: ligar/desligar, editar o texto (com versões) e ver o que foi enviado. */
@RestController
@RequestMapping("/api/v1/messages")
public class MessageSettingsController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final ObjectMapper json;
    private final br.com.lcmtech.optics.identity.EmailDelivery mail;

    public MessageSettingsController(JdbcTemplate jdbc, OpticContext context, ObjectMapper json,
                                     br.com.lcmtech.optics.identity.EmailDelivery mail) {
        this.jdbc = jdbc;
        this.context = context;
        this.json = json;
        this.mail = mail;
    }

    public record Settings(boolean appointmentReminderEnabled, boolean birthdayEmailEnabled, String appointmentTemplate,
                           String birthdayTemplate) {}

    static Map<String, Object> load(JdbcTemplate jdbc, UUID optic) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT appointment_reminder_enabled, birthday_email_enabled, appointment_template, birthday_template, version,
                       updated_at FROM optic_message_settings WHERE optic_id=?""", optic);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("appointment_reminder_enabled", false);
            out.put("birthday_email_enabled", false);
            out.put("appointment_template", MessageTemplates.APPOINTMENT_DEFAULT);
            out.put("birthday_template", MessageTemplates.BIRTHDAY_DEFAULT);
            out.put("version", 0);
            out.put("updated_at", null);
        } else out.putAll(rows.get(0));
        return out;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(Authentication auth, HttpSession session) {
        context.requireMaster(auth, session);
        UUID optic = context.optic(auth, session);
        Map<String, Object> out = load(jdbc, optic);
        out.put("emailConfigured", mail.available());
        out.put("appointmentFields", MessageTemplates.APPOINTMENT_FIELDS.stream().sorted().toList());
        out.put("birthdayFields", MessageTemplates.BIRTHDAY_FIELDS.stream().sorted().toList());
        out.put("optedInCustomers", jdbc.queryForObject("SELECT count(*) FROM customers WHERE optic_id=? AND email_opt_in "
                + "AND email IS NOT NULL", Integer.class, optic));
        out.put("optedInPatients", jdbc.queryForObject("SELECT count(*) FROM optometry_patients WHERE optic_id=? AND reminder_opt_in "
                + "AND email IS NOT NULL", Integer.class, optic));
        return out;
    }

    @PutMapping("/settings")
    @Transactional
    public Map<String, Object> save(@RequestBody Settings body, Authentication auth, HttpSession session) throws Exception {
        context.requireMaster(auth, session);
        if (body == null) throw new IllegalArgumentException("Informe as configurações.");
        UUID optic = context.optic(auth, session), actor = context.accountId(auth);
        String appointment = MessageTemplates.validate(body.appointmentTemplate(), MessageTemplates.APPOINTMENT_FIELDS,
                "lembrete de consulta");
        String birthday = MessageTemplates.validate(body.birthdayTemplate(), MessageTemplates.BIRTHDAY_FIELDS,
                "aviso de aniversário");
        Integer version = jdbc.queryForObject("""
                INSERT INTO optic_message_settings(optic_id,appointment_reminder_enabled,birthday_email_enabled,
                    appointment_template,birthday_template,version,updated_by)
                VALUES(?,?,?,?,?,1,?)
                ON CONFLICT (optic_id) DO UPDATE SET appointment_reminder_enabled=EXCLUDED.appointment_reminder_enabled,
                    birthday_email_enabled=EXCLUDED.birthday_email_enabled, appointment_template=EXCLUDED.appointment_template,
                    birthday_template=EXCLUDED.birthday_template, version=optic_message_settings.version+1,
                    updated_by=EXCLUDED.updated_by, updated_at=now()
                RETURNING version""", Integer.class, optic, body.appointmentReminderEnabled(), body.birthdayEmailEnabled(),
                appointment, birthday, actor);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("appointmentReminderEnabled", body.appointmentReminderEnabled());
        snapshot.put("birthdayEmailEnabled", body.birthdayEmailEnabled());
        snapshot.put("appointmentTemplate", appointment);
        snapshot.put("birthdayTemplate", birthday);
        jdbc.update("INSERT INTO optic_message_settings_history(optic_id,version,settings,changed_by) VALUES(?,?,?::jsonb,?)",
                optic, version, json.writeValueAsString(snapshot), actor);
        return Map.of("saved", true, "version", version);
    }

    /** Últimos avisos gerados (auditoria): para quem, quando, situação. Sem o texto completo na lista. */
    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox(Authentication auth, HttpSession session) {
        context.requireMaster(auth, session);
        return jdbc.queryForList("""
                SELECT id, kind, recipient_email, subject, status, attempts, last_error, created_at, sent_at, template_version
                FROM message_outbox WHERE optic_id=? ORDER BY created_at DESC LIMIT 100""", context.optic(auth, session));
    }

    @GetMapping("/settings/history")
    public List<Map<String, Object>> history(Authentication auth, HttpSession session) {
        context.requireMaster(auth, session);
        return jdbc.queryForList("""
                SELECT h.version, h.settings::text AS settings, h.changed_at, a.full_name AS changed_by
                FROM optic_message_settings_history h JOIN accounts a ON a.id=h.changed_by
                WHERE h.optic_id=? ORDER BY h.version DESC LIMIT 50""", context.optic(auth, session));
    }
}
