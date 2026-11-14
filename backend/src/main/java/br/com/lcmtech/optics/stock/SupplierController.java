package br.com.lcmtech.optics.stock;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Fornecedores e laboratórios. Nada é apagado: desativar mantém o histórico de compras e ordens de serviço. */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;

    public SupplierController(JdbcTemplate jdbc, OpticContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public record Supplier(String name, String kind, String document, String phone, String email, String contactName,
                           String notes) {}

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_READ");
        return jdbc.queryForList("""
                SELECT id,name,kind,document,phone,email,contact_name,notes,active FROM suppliers
                WHERE optic_id=? ORDER BY active DESC, name""", context.optic(auth, session));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Supplier req, Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_WRITE");
        validate(req);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers(optic_id,id,name,kind,document,phone,email,contact_name,notes) VALUES(?,?,?,?,?,?,?,?,?)",
                context.optic(auth, session), id, req.name().trim(), kind(req.kind()), digits(req.document()), digits(req.phone()),
                blank(req.email()), blank(req.contactName()), blank(req.notes()));
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Boolean> update(@PathVariable UUID id, @RequestBody Supplier req, Authentication auth, HttpSession session) {
        context.require(auth, session, "CATALOG_WRITE");
        validate(req);
        int n = jdbc.update("UPDATE suppliers SET name=?,kind=?,document=?,phone=?,email=?,contact_name=?,notes=? WHERE optic_id=? AND id=?",
                req.name().trim(), kind(req.kind()), digits(req.document()), digits(req.phone()), blank(req.email()),
                blank(req.contactName()), blank(req.notes()), context.optic(auth, session), id);
        if (n == 0) throw new NoSuchElementException("Fornecedor não encontrado.");
        return Map.of("updated", true);
    }

    @PatchMapping("/{id}/active")
    public Map<String, Boolean> active(@PathVariable UUID id, @RequestBody Map<String, Boolean> body, Authentication auth,
                                       HttpSession session) {
        context.require(auth, session, "CATALOG_WRITE");
        if (body == null || body.get("active") == null) throw new IllegalArgumentException("Informe active.");
        int n = jdbc.update("UPDATE suppliers SET active=? WHERE optic_id=? AND id=?", body.get("active"),
                context.optic(auth, session), id);
        if (n == 0) throw new NoSuchElementException("Fornecedor não encontrado.");
        return Map.of("updated", true);
    }

    private static void validate(Supplier req) {
        if (req == null || req.name() == null || req.name().isBlank()) throw new IllegalArgumentException("Informe o nome do fornecedor.");
        if (req.name().length() > 160) throw new IllegalArgumentException("Nome muito longo.");
        String doc = digits(req.document());
        if (doc != null && doc.length() != 11 && doc.length() != 14) throw new IllegalArgumentException("CNPJ ou CPF inválido.");
        if (req.notes() != null && req.notes().length() > 500) throw new IllegalArgumentException("Observação muito longa.");
        if (blank(req.email()) != null) br.com.lcmtech.optics.customers.CustomerController.email(req.email());
    }

    private static String kind(String kind) {
        if (kind == null || kind.isBlank()) return "FORNECEDOR";
        if (!List.of("FORNECEDOR", "LABORATORIO", "AMBOS").contains(kind)) throw new IllegalArgumentException("Tipo inválido.");
        return kind;
    }

    static String digits(String v) {
        if (v == null) return null;
        String d = v.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static void requireSupplier(JdbcTemplate jdbc, UUID optic, UUID supplier) {
        if (supplier == null) return;
        java.util.List<Boolean> active = jdbc.queryForList("SELECT active FROM suppliers WHERE optic_id=? AND id=?", Boolean.class,
                optic, supplier);
        if (active.isEmpty()) throw new NoSuchElementException("Fornecedor não encontrado.");
        if (!Boolean.TRUE.equals(active.get(0)))
            throw new IllegalArgumentException("Este fornecedor está desativado. Reative-o para usar em novas entradas e OS.");
    }
}
