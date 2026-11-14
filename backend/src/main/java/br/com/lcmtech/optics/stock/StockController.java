package br.com.lcmtech.optics.stock;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Estoque dos produtos que a ótica escolhe controlar: saldo, mínimo, alerta e movimentos com histórico. */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;

    public StockController(JdbcTemplate jdbc, OpticContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    /** Produtos com estoque controlado e o saldo atual; "low" quando está no mínimo ou abaixo. */
    @GetMapping
    public List<Map<String, Object>> items(Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_READ");
        return jdbc.queryForList("""
                SELECT i.id, i.kind, i.brand, i.model, i.category, i.min_stock, i.active,
                       coalesce(sum(m.quantity),0) AS quantity,
                       coalesce(sum(m.quantity),0) <= i.min_stock AS low,
                       max(m.created_at) AS last_movement_at
                FROM catalog_items i LEFT JOIN stock_movements m ON m.optic_id=i.optic_id AND m.item_id=i.id
                WHERE i.optic_id=? AND i.track_stock
                GROUP BY i.optic_id, i.id ORDER BY (coalesce(sum(m.quantity),0) <= i.min_stock) DESC, i.kind, i.brand, i.model""",
                context.optic(auth, session));
    }

    public record Tracking(boolean trackStock, Integer minStock) {}

    /** Liga/desliga o controle de estoque de um produto e define o mínimo. */
    @PatchMapping("/items/{itemId}")
    public Map<String, Boolean> tracking(@PathVariable UUID itemId, @RequestBody Tracking req, Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_WRITE");
        int min = req == null || req.minStock() == null ? 0 : req.minStock();
        if (min < 0 || min > 100000) throw new IllegalArgumentException("Estoque mínimo inválido.");
        int n = jdbc.update("UPDATE catalog_items SET track_stock=?, min_stock=? WHERE optic_id=? AND id=?",
                req != null && req.trackStock(), min, context.optic(auth, session), itemId);
        if (n == 0) throw new NoSuchElementException("Produto não encontrado.");
        return Map.of("updated", true);
    }

    public record Movement(UUID itemId, String kind, Integer quantity, BigDecimal unitCost, UUID supplierId, String note) {}

    /** Entrada (compra) ou ajuste (contagem, perda). Saída por venda e devolução são automáticas. */
    @PostMapping("/movements")
    @Transactional
    public Map<String, Object> move(@RequestBody Movement req, Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_WRITE");
        UUID optic = context.optic(auth, session);
        if (req == null || req.itemId() == null || req.quantity() == null) throw new IllegalArgumentException("Informe produto e quantidade.");
        if (!List.of("ENTRADA", "AJUSTE").contains(req.kind()))
            throw new IllegalArgumentException("Use entrada ou ajuste; a saída por venda é automática.");
        if (req.quantity() == 0 || Math.abs(req.quantity()) > 10000) throw new IllegalArgumentException("Quantidade inválida.");
        if ("ENTRADA".equals(req.kind()) && req.quantity() < 0) throw new IllegalArgumentException("Entrada deve ser positiva.");
        if ("AJUSTE".equals(req.kind()) && (req.note() == null || req.note().isBlank()))
            throw new IllegalArgumentException("Explique o ajuste (ex.: contagem, perda, avaria).");
        if (req.unitCost() != null && req.unitCost().signum() < 0) throw new IllegalArgumentException("Custo inválido.");
        if (req.note() != null && req.note().length() > 200) throw new IllegalArgumentException("Observação muito longa.");
        // FOR UPDATE: dois ajustes ao mesmo tempo não passam juntos pela conferência do saldo.
        List<Boolean> tracked = jdbc.queryForList("SELECT track_stock FROM catalog_items WHERE optic_id=? AND id=? FOR UPDATE",
                Boolean.class, optic, req.itemId());
        if (tracked.isEmpty()) throw new NoSuchElementException("Produto não encontrado.");
        if (!tracked.get(0)) throw new IllegalArgumentException("Ative o controle de estoque deste produto primeiro.");
        SupplierController.requireSupplier(jdbc, optic, req.supplierId());
        if (req.quantity() < 0) {
            Integer current = jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM stock_movements WHERE optic_id=? AND item_id=?",
                    Integer.class, optic, req.itemId());
            if (current + req.quantity() < 0)
                throw new IllegalArgumentException("O estoque ficaria negativo: há " + Math.max(current, 0)
                        + " unidade(s) deste produto. Confira a contagem.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stock_movements(optic_id,id,item_id,kind,quantity,unit_cost,supplier_id,note,created_by)
                VALUES(?,?,?,?,?,?,?,?,?)""", optic, id, req.itemId(), req.kind(), req.quantity(),
                req.unitCost() == null ? null : req.unitCost().setScale(2, java.math.RoundingMode.HALF_UP), req.supplierId(),
                SupplierController.blank(req.note()), context.accountId(auth));
        Integer balance = jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM stock_movements WHERE optic_id=? AND item_id=?",
                Integer.class, optic, req.itemId());
        return Map.of("id", id, "quantity", balance);
    }

    @GetMapping("/items/{itemId}/movements")
    public List<Map<String, Object>> movements(@PathVariable UUID itemId, Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_READ");
        UUID optic = context.optic(auth, session);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM catalog_items WHERE optic_id=? AND id=?", Integer.class, optic, itemId);
        if (n == null || n == 0) throw new NoSuchElementException("Produto não encontrado.");
        return jdbc.queryForList("""
                SELECT m.id, m.kind, m.quantity, m.unit_cost, m.note, m.created_at, s.name AS supplier, a.full_name AS created_by
                FROM stock_movements m LEFT JOIN suppliers s ON s.optic_id=m.optic_id AND s.id=m.supplier_id
                JOIN accounts a ON a.id=m.created_by
                WHERE m.optic_id=? AND m.item_id=? ORDER BY m.created_at DESC LIMIT 200""", optic, itemId);
    }
}
