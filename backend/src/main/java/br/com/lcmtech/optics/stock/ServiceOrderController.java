package br.com.lcmtech.optics.stock;

import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.OpticClock;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Ordens de serviço (OS): pedido de lentes ao laboratório ou conserto de óculos do cliente. Número sequencial por
 * ótica, situação com histórico (quem mudou e quando) e aviso de atraso pela data prevista.
 */
@RestController
@RequestMapping("/api/v1/service-orders")
public class ServiceOrderController {
    /** Situação atual → próximas permitidas. */
    static final Map<String, Set<String>> FLOW = Map.of(
            "ABERTA", Set.of("ENVIADA", "RECEBIDA", "CANCELADA"),
            "ENVIADA", Set.of("RECEBIDA", "CANCELADA"),
            "RECEBIDA", Set.of("ENTREGUE", "CANCELADA"),
            "ENTREGUE", Set.of(),
            "CANCELADA", Set.of());

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticClock clock;

    public ServiceOrderController(JdbcTemplate jdbc, OpticContext context, OpticClock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String status, Authentication auth, HttpSession session) {
        context.require(auth, session, "SALES_READ");
        UUID optic = context.optic(auth, session);
        if (!status.isEmpty() && !FLOW.containsKey(status) && !"EM_ANDAMENTO".equals(status))
            throw new IllegalArgumentException("Situação inválida.");
        LocalDate today = clock.today(optic);
        return jdbc.queryForList("""
                SELECT o.id, o.number, o.kind, o.status, o.description, o.price, o.sent_on, o.expected_on, o.received_on,
                       o.delivered_on, o.created_at, o.sale_id, c.full_name AS customer, c.phone AS customer_phone,
                       s.name AS supplier, (o.expected_on < ? AND o.status IN ('ABERTA','ENVIADA')) AS late
                FROM service_orders o JOIN customers c ON c.optic_id=o.optic_id AND c.id=o.customer_id
                LEFT JOIN suppliers s ON s.optic_id=o.optic_id AND s.id=o.supplier_id
                WHERE o.optic_id=? AND (?='' OR o.status=? OR (?='EM_ANDAMENTO' AND o.status IN ('ABERTA','ENVIADA','RECEBIDA')))
                ORDER BY (o.status IN ('ENTREGUE','CANCELADA')), o.expected_on NULLS LAST, o.number DESC LIMIT 300""",
                java.sql.Date.valueOf(today), optic, status, status, status);
    }

    public record NewOrder(String kind, UUID saleId, UUID customerId, UUID supplierId, String description, LocalDate expectedOn,
                           BigDecimal price) {}

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody NewOrder req, Authentication auth, HttpSession session) {
        context.require(auth, session, "SALES_DELIVER");
        UUID optic = context.optic(auth, session), actor = context.accountId(auth);
        if (req == null || !List.of("LENTES", "CONSERTO").contains(req.kind())) throw new IllegalArgumentException("Escolha lentes ou conserto.");
        if (req.description() == null || req.description().isBlank()) throw new IllegalArgumentException("Descreva o serviço.");
        if (req.description().length() > 500) throw new IllegalArgumentException("Descrição muito longa.");
        if (req.price() != null && req.price().signum() < 0) throw new IllegalArgumentException("Valor inválido.");
        UUID customer = req.customerId();
        if (req.saleId() != null) {
            List<UUID> owner = jdbc.queryForList("SELECT customer_id FROM sales WHERE optic_id=? AND id=?", UUID.class, optic, req.saleId());
            if (owner.isEmpty()) throw new NoSuchElementException("Venda não encontrada.");
            customer = owner.get(0);
        }
        if (customer == null) throw new IllegalArgumentException("Escolha o cliente ou a venda.");
        Integer known = jdbc.queryForObject("SELECT count(*) FROM customers WHERE optic_id=? AND id=?", Integer.class, optic, customer);
        if (known == null || known == 0) throw new NoSuchElementException("Cliente não encontrado.");
        SupplierController.requireSupplier(jdbc, optic, req.supplierId());
        jdbc.queryForList("SELECT id FROM optics WHERE id=? FOR UPDATE", optic); // número sequencial sem repetir
        Long number = jdbc.queryForObject("SELECT coalesce(max(number),0)+1 FROM service_orders WHERE optic_id=?", Long.class, optic);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_orders(optic_id,id,number,kind,sale_id,customer_id,supplier_id,description,price,expected_on,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""", optic, id, number, req.kind(), req.saleId(), customer, req.supplierId(),
                req.description().trim(), req.price(), req.expectedOn(), actor);
        event(optic, id, null, "ABERTA", actor);
        return Map.of("id", id, "number", number);
    }

    public record StatusChange(String status, LocalDate date) {}

    @PatchMapping("/{id}/status")
    @Transactional
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusChange req, Authentication auth, HttpSession session) {
        context.require(auth, session, "SALES_DELIVER");
        UUID optic = context.optic(auth, session), actor = context.accountId(auth);
        List<String> current = jdbc.queryForList("SELECT status FROM service_orders WHERE optic_id=? AND id=? FOR UPDATE",
                String.class, optic, id);
        if (current.isEmpty()) throw new NoSuchElementException("Ordem de serviço não encontrada.");
        String from = current.get(0), to = req == null ? null : req.status();
        if (to == null || !FLOW.containsKey(to)) throw new IllegalArgumentException("Situação inválida.");
        if (!FLOW.get(from).contains(to))
            throw new IllegalArgumentException("Não é possível passar de " + label(from) + " para " + label(to) + ".");
        LocalDate today = clock.today(optic);
        LocalDate day = req.date() == null ? today : req.date();
        if (day.isAfter(today)) throw new IllegalArgumentException("A data não pode ser futura.");
        String column = switch (to) {
            case "ENVIADA" -> "sent_on";
            case "RECEBIDA" -> "received_on";
            case "ENTREGUE" -> "delivered_on";
            default -> null;
        };
        jdbc.update("UPDATE service_orders SET status=?, updated_at=now()" + (column == null ? "" : ", " + column + "=?")
                + " WHERE optic_id=? AND id=?", column == null ? new Object[]{to, optic, id} : new Object[]{to, day, optic, id});
        event(optic, id, from, to, actor);
        return Map.of("status", to);
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable UUID id, Authentication auth, HttpSession session) {
        context.require(auth, session, "SALES_READ");
        UUID optic = context.optic(auth, session);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM service_orders WHERE optic_id=? AND id=?", Integer.class, optic, id);
        if (n == null || n == 0) throw new NoSuchElementException("Ordem de serviço não encontrada.");
        return jdbc.queryForList("""
                SELECT e.previous_status, e.next_status, e.occurred_at, a.full_name AS actor
                FROM service_order_events e JOIN accounts a ON a.id=e.actor_id
                WHERE e.optic_id=? AND e.order_id=? ORDER BY e.occurred_at""", optic, id);
    }

    private void event(UUID optic, UUID order, String from, String to, UUID actor) {
        jdbc.update("INSERT INTO service_order_events(optic_id,id,order_id,previous_status,next_status,actor_id) VALUES(?,?,?,?,?,?)",
                optic, UUID.randomUUID(), order, from, to, actor);
    }

    private static String label(String status) {
        return switch (status) {
            case "ABERTA" -> "aberta";
            case "ENVIADA" -> "enviada ao laboratório";
            case "RECEBIDA" -> "recebida na loja";
            case "ENTREGUE" -> "entregue ao cliente";
            default -> "cancelada";
        };
    }
}
