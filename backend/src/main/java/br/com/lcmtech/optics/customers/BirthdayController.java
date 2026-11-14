package br.com.lcmtech.optics.customers;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Aniversários de clientes; abertura WhatsApp NÃO significa mensagem entregue. */
@RestController
@RequestMapping("/api/v1/birthdays")
public class BirthdayController {
    private final JdbcTemplate jdbc;
    private final OpticContext access;

    public BirthdayController(JdbcTemplate jdbc,OpticContext access) {
        this.jdbc=jdbc;this.access=access;
    }

    public record Birthday(UUID customerId,String name,String phone,
           boolean whatsappOptIn,LocalDate birthday,long daysUntil) {}

    public record BirthdayStatus(boolean showModal,LocalDate today,List<Birthday> birthdays) {}

    private LocalDate today(UUID optic) {
        String tz=jdbc.query("""
                SELECT time_zone FROM optic_business_calendar WHERE optic_id=?
                """,(rs,n)->rs.getString(1),optic).stream().findFirst()
                .orElse("America/Fortaleza");
        return LocalDate.now(ZoneId.of(tz));
    }

    /** Em anos comuns, a ótica considera aniversários de 29/02 em 28/02. */
    public static LocalDate birthdayInYear(LocalDate born,int year) {
        MonthDay birthday=MonthDay.from(born);
        if(!birthday.isValidYear(year))return LocalDate.of(year,2,28);
        return birthday.atYear(year);
    }

    public List<Birthday> find(UUID optic,LocalDate reference,int days) {
        if(days<0||days>366)throw new IllegalArgumentException("O período deve ter até 366 dias.");
        List<Birthday> result=new ArrayList<>();
        jdbc.query("""
                SELECT id,full_name,phone,birth_date,birthday_whatsapp_opt_in
                FROM customers WHERE optic_id=? AND birth_date IS NOT NULL
                """,rs->{
            LocalDate born=rs.getDate("birth_date").toLocalDate();
            LocalDate next=birthdayInYear(born,reference.getYear());
            if(next.isBefore(reference))next=birthdayInYear(born,reference.getYear()+1);
            long remaining=ChronoUnit.DAYS.between(reference,next);
            if(remaining<=days)result.add(new Birthday(
                    (UUID)rs.getObject("id"),rs.getString("full_name"),
                    rs.getString("phone"),rs.getBoolean("birthday_whatsapp_opt_in"),
                    next,remaining));
        },optic);
        result.sort(Comparator.comparingLong(Birthday::daysUntil).thenComparing(Birthday::name));
        return result;
    }

    @GetMapping
    public List<Birthday> list(@RequestParam(defaultValue="30") int days,
            Authentication auth,HttpSession session) {
        access.require(auth,session,"CUSTOMERS_READ");
        UUID optic=access.optic(auth,session);
        return find(optic,today(optic),days);
    }

    @GetMapping("/status")
    public BirthdayStatus status(Authentication auth,HttpSession session) {
        access.require(auth,session,"CUSTOMERS_READ");
        UUID optic=access.optic(auth,session),account=access.accountId(auth);
        LocalDate now=today(optic);
        List<Birthday> todayBirthdays=find(optic,now,0);
        Integer seen=jdbc.queryForObject("""
                SELECT count(*) FROM birthday_modal_views
                WHERE optic_id=? AND account_id=? AND viewed_on=?
                """,Integer.class,optic,account,java.sql.Date.valueOf(now));
        return new BirthdayStatus(!todayBirthdays.isEmpty() && (seen==null||seen==0),
                now,todayBirthdays);
    }

    @PostMapping("/dismiss")
    @Transactional
    public Map<String,Boolean> dismiss(Authentication auth,HttpSession session) {
        access.require(auth,session,"CUSTOMERS_READ");
        UUID optic=access.optic(auth,session);
        jdbc.update("""
            INSERT INTO birthday_modal_views(optic_id,account_id,viewed_on)
            VALUES(?,?,?) ON CONFLICT DO NOTHING
            """,optic,access.accountId(auth),java.sql.Date.valueOf(today(optic)));
        return Map.of("acknowledged",true);
    }

    public record Consent(boolean allowWhatsapp) {}

    @PutMapping("/{customerId}/consent")
    @Transactional
    public Map<String,Boolean> updateConsent(@PathVariable UUID customerId,
            @RequestBody Consent request,Authentication auth,HttpSession session) {
        access.require(auth,session,"CUSTOMERS_WRITE");
        if(request==null)throw new IllegalArgumentException("Confirme a escolha de comunicação.");
        UUID optic=access.optic(auth,session);
        int changed=jdbc.update("""
            UPDATE customers SET birthday_whatsapp_opt_in=?,updated_at=now()
            WHERE optic_id=? AND id=?
            """,request.allowWhatsapp(),optic,customerId);
        if(changed!=1)throw new java.util.NoSuchElementException("Cliente não encontrado.");
        jdbc.update("""
            INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,?,'BIRTHDAY_WHATSAPP_PREFERENCE_UPDATED','CUSTOMER',?)
            """,UUID.randomUUID(),optic,access.accountId(auth),customerId);
        return Map.of("saved",true);
    }

    @PostMapping("/{customerId}/whatsapp-opened")
    @Transactional
    public Map<String,Boolean> opened(@PathVariable UUID customerId,
            Authentication auth,HttpSession session) {
        access.require(auth,session,"CUSTOMERS_READ");
        UUID optic=access.optic(auth,session);
        Integer allowed=jdbc.queryForObject("""
            SELECT count(*) FROM customers WHERE optic_id=? AND id=?
              AND birthday_whatsapp_opt_in AND phone IS NOT NULL
            """,Integer.class,optic,customerId);
        if(allowed==null||allowed!=1)
            throw new IllegalArgumentException("Não há consentimento e telefone para este contato.");
        jdbc.update("""
            INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,?,'BIRTHDAY_WHATSAPP_OPENED','CUSTOMER',?)
            """,UUID.randomUUID(),optic,access.accountId(auth),customerId);
        return Map.of("opened",true);
    }
}
