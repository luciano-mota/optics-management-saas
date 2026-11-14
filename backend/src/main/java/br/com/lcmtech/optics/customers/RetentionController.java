package br.com.lcmtech.optics.customers;

import br.com.lcmtech.optics.shop.OpticClock;
import br.com.lcmtech.optics.customers.domain.ReturnReminderPolicy;
import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Retenção: a lista é calculada a partir da venda mais recente de cada cliente.
 * A marcação WHATSAPP_CLICK indica que alguém abriu a conversa, não que
 * o WhatsApp entregou/confirmou uma mensagem.
 */
@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {
    private final JdbcTemplate jdbc;
    private final OpticContext access;

    private final OpticClock clock;
    public RetentionController(JdbcTemplate jdbc, OpticContext access, OpticClock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public record Reminder(UUID saleId, UUID customerId, String customerName, String phone,
                           int milestoneMonths, int replacementMonths,
                           LocalDate lastSaleDate, LocalDate reminderDate, long daysUntil,
                           boolean whatsappOpened) {}

    @GetMapping
    public List<Reminder> list(
            @RequestParam(defaultValue = "0") int month,
            @RequestParam(defaultValue = "30") int days,
            Authentication auth, HttpSession session) {
        access.require(auth, session, "CUSTOMERS_READ");
        if (month != 0 && !List.of(3, 6, 9, 12).contains(month)) {
            throw new IllegalArgumentException("Escolha 3, 6, 9 ou 12 meses.");
        }
        if (days < 0 || days > 365) {
            throw new IllegalArgumentException("O período deve ser de 0 a 365 dias.");
        }
        UUID optic = access.optic(auth, session);
        LocalDate today = clock.today(optic);
        // 15 dias retroativos para não perder um alerta durante folgas e feriados.
        LocalDate oldest = today.minusMonths(12).minusDays(16);
        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT DISTINCT ON (s.customer_id)
                    s.id AS sale_id, s.customer_id, c.full_name, c.phone,
                    s.sold_on, s.replacement_months
                FROM sales s
                JOIN customers c ON c.optic_id = s.optic_id AND c.id = s.customer_id
                WHERE s.optic_id = ? AND s.status <> 'CANCELLED'
                    AND s.sold_on >= ?
                ORDER BY s.customer_id, s.sold_on DESC, s.created_at DESC
                LIMIT 3000
                """, optic, oldest);

        java.util.Map<UUID, java.util.Set<Integer>> clicked=new java.util.HashMap<>();
        jdbc.query("""
                SELECT sale_id,months FROM reminder_events
                WHERE optic_id=? AND channel='WHATSAPP_CLICK'
                """, rs -> {
            UUID sale=(UUID)rs.getObject(1);
            clicked.computeIfAbsent(sale,ignored->new java.util.HashSet<>()).add(rs.getInt(2));
        }, optic);
        List<Reminder> due = new ArrayList<>();
        for (var row : recent) {
            UUID sale = (UUID) row.get("sale_id");
            UUID customer = (UUID) row.get("customer_id");
            LocalDate sold = ((java.sql.Date) row.get("sold_on")).toLocalDate();
            int replacement = (Integer) row.get("replacement_months");
            for (var milestone : ReturnReminderPolicy.milestonesFrom(sold)) {
                if (month != 0 && month != milestone.month()) continue;
                long remaining = ChronoUnit.DAYS.between(today, milestone.date());
                if (remaining < -15 || remaining > days) continue;
                boolean opened=clicked.getOrDefault(sale,java.util.Set.of())
                        .contains(milestone.month());
                due.add(new Reminder(sale, customer,
                        (String) row.get("full_name"), (String) row.get("phone"),
                        milestone.month(), replacement, sold, milestone.date(),
                        remaining, opened));
            }
        }
        due.sort(java.util.Comparator.comparing(Reminder::reminderDate)
                .thenComparing(Reminder::customerName));
        return due.stream().limit(300).toList();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(Authentication auth, HttpSession session) {
        List<Reminder> items = list(0, 30, auth, session);
        long newContacts = items.stream().filter(item -> !item.whatsappOpened()).count();
        return Map.of(
                "dueSoon", items.size(),
                "awaitingContact", newContacts,
                "threeMonths", items.stream().filter(i -> i.milestoneMonths() == 3).count(),
                "sixMonths", items.stream().filter(i -> i.milestoneMonths() == 6).count(),
                "nineMonths", items.stream().filter(i -> i.milestoneMonths() == 9).count(),
                "twelveMonths", items.stream().filter(i -> i.milestoneMonths() == 12).count());
    }

    public record Contact(int milestoneMonths) {}
    @PostMapping("/{saleId}/contact-opened")
    @Transactional
    public Map<String, Boolean> logWhatsAppOpened(
            @PathVariable UUID saleId, @RequestBody Contact request,
            Authentication auth, HttpSession session) {
        access.require(auth, session, "CUSTOMERS_READ");
        UUID optic = access.optic(auth, session);
        if (request == null || !List.of(3, 6, 9, 12).contains(request.milestoneMonths())) {
            throw new IllegalArgumentException("Marco de relacionamento inválido.");
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM sales
                WHERE optic_id = ? AND id = ? AND status <> 'CANCELLED'
                """, Integer.class, optic, saleId);
        if (count == null || count != 1) {
            throw new java.util.NoSuchElementException("Venda não encontrada nesta ótica.");
        }
        jdbc.update("""
                INSERT INTO reminder_events(optic_id,sale_id,months,channel)
                VALUES(?,?,?,'WHATSAPP_CLICK') ON CONFLICT DO NOTHING
                """, optic, saleId, request.milestoneMonths());
        jdbc.update("""
                INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id)
                VALUES(?,?,?,'RETENTION_WHATSAPP_OPENED','SALE',?)
                """, UUID.randomUUID(), optic, access.accountId(auth), saleId);
        return Map.of("recorded", true);
    }
}
