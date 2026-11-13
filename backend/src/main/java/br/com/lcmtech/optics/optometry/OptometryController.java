package br.com.lcmtech.optics.optometry;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Consultas das clínicas parceiras são independentes das vendas.
 * Uma consulta NÃO transforma paciente em cliente.
 */
@RestController
@RequestMapping("/api/v1/optometry")
public class OptometryController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final br.com.lcmtech.optics.shop.OpticClock clock;
    public OptometryController(JdbcTemplate jdbc,OpticContext context,br.com.lcmtech.optics.shop.OpticClock clock){
        this.jdbc=jdbc;this.context=context;this.clock=clock;
    }

    /** reminderOptIn: o paciente autorizou receber lembrete de consulta por e-mail. */
    public record Patient(String fullName,String cpf,java.time.LocalDate birthDate,
            String phone,String email,String address,Boolean reminderOptIn){}

    @GetMapping("/patients")
    public List<Map<String,Object>> patients(
            @RequestParam(defaultValue="") String q,Authentication auth,HttpSession session) {
        context.require(auth,session,"PATIENTS_READ");
        String like="%"+q.trim()+"%";
        return jdbc.queryForList("""
            SELECT p.id,p.full_name,p.cpf,p.birth_date,p.phone,p.email,p.address,p.reminder_opt_in,
                (c.id IS NOT NULL) AS converted,
                c.id AS customer_id
            FROM optometry_patients p
            LEFT JOIN customers c ON c.optic_id=p.optic_id AND c.source_patient_id=p.id
            WHERE p.optic_id=? AND (lower(p.full_name) LIKE lower(?) OR p.cpf LIKE ?)
            ORDER BY p.full_name LIMIT 300
            """,context.optic(auth,session),like,like);
    }

    @GetMapping("/patients/suggestions")
    public List<Map<String,Object>> suggestions(
            @RequestParam(defaultValue="") String q,Authentication auth,HttpSession session) {
        context.require(auth,session,"PATIENTS_READ");
        if(q.trim().length()<2)return List.of();
        return jdbc.query("""
            SELECT p.id,p.full_name,p.birth_date,p.phone
            FROM optometry_patients p
            WHERE p.optic_id=? AND lower(p.full_name) LIKE lower(?)
              AND NOT EXISTS (
                 SELECT 1 FROM customers c WHERE c.optic_id=p.optic_id
                 AND (c.source_patient_id=p.id OR
                      (p.cpf IS NOT NULL AND c.cpf=p.cpf)))
            ORDER BY p.full_name LIMIT 20
            """, (rs,n)->Map.<String,Object>of(
                    "id",rs.getObject("id"),
                    "name",rs.getString("full_name"),
                    "birthDate",rs.getString("birth_date")==null?"":rs.getString("birth_date"),
                    "phoneSuffix",suffix(rs.getString("phone"))),
            context.optic(auth,session),"%"+q.trim()+"%");
    }

    private static String suffix(String phone){
        if(phone==null)return "";
        String only=phone.replaceAll("\\D","");
        return only.length()>4?"••••"+only.substring(only.length()-4):"";
    }

    @GetMapping("/patients/{id}/sales-prefill")
    public Map<String,Object> salesPrefill(@PathVariable UUID id,
             Authentication auth,HttpSession session) {
        context.require(auth,session,"PATIENTS_READ");
        UUID optic=context.optic(auth,session);
        return jdbc.queryForList("""
            SELECT p.id,p.full_name,p.cpf,p.birth_date,p.phone,p.email,p.address
            FROM optometry_patients p WHERE p.optic_id=? AND p.id=?
            """,optic,id).stream().findFirst()
            .orElseThrow(()->new IllegalArgumentException("Paciente não encontrado."));
    }

    @PostMapping("/patients")
    @Transactional
    public Map<String,Object> createPatient(@RequestBody Patient req,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"PATIENTS_WRITE");
        if(req==null||req.fullName()==null||req.fullName().isBlank())
            throw new IllegalArgumentException("Informe o nome do paciente.");
        String cpf=normalize(req.cpf());
        String email=br.com.lcmtech.optics.customers.CustomerController.email(req.email());
        boolean optIn=Boolean.TRUE.equals(req.reminderOptIn())&&email!=null;
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO optometry_patients(optic_id,id,full_name,cpf,birth_date,phone,
                email,address,created_by,reminder_opt_in,reminder_opt_in_at) VALUES(?,?,?,?,?,?,?,?,?,?,CASE WHEN ? THEN now() END)
            """,context.optic(auth,session),id,req.fullName().trim(),cpf,
                req.birthDate(),req.phone(),email,req.address(),context.accountId(auth),optIn,optIn);
        return Map.of("id",id);
    }

    public record ReminderConsent(String email,Boolean reminderOptIn){}

    /** Atualiza o e-mail e a autorização de lembrete por e-mail do paciente. */
    @PutMapping("/patients/{id}/reminder")
    public Map<String,Boolean> reminder(@PathVariable UUID id,@RequestBody ReminderConsent req,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"PATIENTS_WRITE");
        String email=br.com.lcmtech.optics.customers.CustomerController.email(req==null?null:req.email());
        boolean optIn=req!=null&&Boolean.TRUE.equals(req.reminderOptIn())&&email!=null;
        int n=jdbc.update("""
            UPDATE optometry_patients SET email=?,reminder_opt_in=?,
                reminder_opt_in_at=CASE WHEN ? AND NOT reminder_opt_in THEN now() WHEN ? THEN reminder_opt_in_at END
            WHERE optic_id=? AND id=?""",email,optIn,optIn,optIn,context.optic(auth,session),id);
        if(n==0)throw new java.util.NoSuchElementException("Paciente não encontrado.");
        return Map.of("reminderOptIn",optIn);
    }

    private static String normalize(String cpf){
        if(cpf==null||cpf.isBlank())return null;
        String result=cpf.replaceAll("\\D","");
        if(!result.matches("\\d{11}"))
            throw new IllegalArgumentException("CPF deve possuir 11 números.");
        return result;
    }

    public record Clinic(String name,String phone,String address){}

    @GetMapping("/clinics")
    public List<Map<String,Object>> clinics(Authentication auth,HttpSession session) {
        context.require(auth,session,"APPOINTMENTS_READ");
        return jdbc.queryForList("SELECT id,name,phone,address,active FROM optical_partner_clinics "
                +"WHERE optic_id=? ORDER BY name",context.optic(auth,session));
    }

    @PostMapping("/clinics")
    public Map<String,Object> createClinic(@RequestBody Clinic req,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"PARTNER_CLINICS_MANAGE");
        if(req==null||req.name()==null||req.name().isBlank())
            throw new IllegalArgumentException("Informe o nome da clínica parceira.");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name,phone,address) "
                +"VALUES(?,?,?,?,?)",context.optic(auth,session),id,req.name(),req.phone(),req.address());
        return Map.of("id",id);
    }

    public record Appointment(UUID patientId,UUID clinicId,
            OffsetDateTime startsAt,OffsetDateTime endsAt,String notes){}

    @GetMapping("/appointments")
    public List<Map<String,Object>> appointments(
            @RequestParam(required=false) java.time.LocalDate day,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"APPOINTMENTS_READ");
        UUID optic=context.optic(auth,session);
        String zone=clock.zone(optic).getId(); // "o dia" no fuso da ótica, não um fuso fixo
        return jdbc.queryForList("""
            SELECT a.id,a.patient_id,a.clinic_id,a.starts_at,a.ends_at,a.status,a.notes,
                p.full_name AS patient_name,p.phone AS patient_phone,
                cl.name AS clinic_name,creator.full_name AS booked_by
            FROM optical_appointments a
            JOIN optometry_patients p ON p.optic_id=a.optic_id AND p.id=a.patient_id
            JOIN optical_partner_clinics cl ON cl.optic_id=a.optic_id AND cl.id=a.clinic_id
            JOIN accounts creator ON creator.id=a.created_by
            WHERE a.optic_id=? AND (?::date IS NULL
                OR (a.starts_at AT TIME ZONE ?)::date=?::date)
            ORDER BY a.starts_at DESC LIMIT 300
            """,optic,day,zone,day);
    }

    @PostMapping("/appointments")
    @Transactional
    public Map<String,Object> createAppointment(@RequestBody Appointment req,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"APPOINTMENTS_MANAGE");
        if(req==null||req.patientId()==null||req.clinicId()==null||
                req.startsAt()==null||req.endsAt()==null||
                !req.endsAt().isAfter(req.startsAt()))
            throw new IllegalArgumentException("Informe paciente, clínica e horário válido.");
        UUID optic=context.optic(auth,session);
        Integer person=jdbc.queryForObject(
                "SELECT count(*) FROM optometry_patients WHERE optic_id=? AND id=?",
                Integer.class,optic,req.patientId());
        Integer clinic=jdbc.queryForObject(
                "SELECT count(*) FROM optical_partner_clinics WHERE optic_id=? AND id=? AND active",
                Integer.class,optic,req.clinicId());
        if(person==null||person==0||clinic==null||clinic==0)
            throw new IllegalArgumentException("Paciente ou clínica não encontrados.");
        // Lock de transação por par ótica-clínica impede corrida de dois agendamentos simultâneos.
        jdbc.queryForObject("SELECT id FROM optical_partner_clinics WHERE optic_id=? AND id=? FOR UPDATE",
                UUID.class,optic,req.clinicId());
        Integer overlap=jdbc.queryForObject("""
                SELECT count(*) FROM optical_appointments
                WHERE optic_id=? AND clinic_id=?
                AND status IN ('SCHEDULED','CONFIRMED')
                AND starts_at < ? AND ends_at > ?
                """,Integer.class,optic,req.clinicId(),
                Timestamp.from(req.endsAt().toInstant()),
                Timestamp.from(req.startsAt().toInstant()));
        if(overlap!=null&&overlap>0)
            throw new IllegalArgumentException("Já existe consulta nesse horário para a clínica.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO optical_appointments(optic_id,id,patient_id,clinic_id,
                starts_at,ends_at,notes,created_by)
            VALUES(?,?,?,?,?,?,?,?)
            """,optic,id,req.patientId(),req.clinicId(),
                Timestamp.from(req.startsAt().toInstant()),
                Timestamp.from(req.endsAt().toInstant()),
                req.notes(),context.accountId(auth));
        return Map.of("id",id);
    }

    public record AppointmentChange(String status){}

    @PatchMapping("/appointments/{id}/status")
    @Transactional
    public Map<String,Boolean> changeStatus(@PathVariable UUID id,
            @RequestBody AppointmentChange req,Authentication auth,HttpSession session) {
        context.require(auth,session,"APPOINTMENTS_MANAGE");
        UUID optic=context.optic(auth,session);
        String previous=jdbc.query("SELECT status FROM optical_appointments "
                +"WHERE optic_id=? AND id=? FOR UPDATE",
                (rs,n)->rs.getString(1),optic,id).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("Consulta não encontrada."));
        String target=req==null?"":req.status();
        boolean valid=switch(previous) {
            case "SCHEDULED" -> List.of("CONFIRMED","COMPLETED","NO_SHOW","CANCELLED").contains(target);
            case "CONFIRMED" -> List.of("COMPLETED","NO_SHOW","CANCELLED").contains(target);
            default -> false;
        };
        if(!valid)throw new IllegalArgumentException("Essa alteração não é permitida.");
        jdbc.update("UPDATE optical_appointments SET status=?,updated_at=now(),updated_by=? "
                +"WHERE optic_id=? AND id=?",target,context.accountId(auth),optic,id);
        jdbc.update("INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id) "
                +"VALUES(?,?,?,'APPOINTMENT_STATUS_CHANGED','APPOINTMENT',?)",
                UUID.randomUUID(),optic,context.accountId(auth),id);
        return Map.of("updated",true);
    }
}
