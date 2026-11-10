package br.com.lcmtech.optics.security;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Tenant e permissões definidos no servidor. Nunca aceitar optic_id enviado
 * pelo browser como prova de autorização.
 */
@Service
public class OpticContext {
    private final JdbcTemplate jdbc;
    public OpticContext(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID accountId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Entre na sua conta.");
        return jdbc.query("SELECT id FROM accounts WHERE lower(email)=lower(?) AND active=true",
                (rs, n) -> (UUID) rs.getObject(1), auth.getName()).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Conta inválida."));
    }

    public UUID optic(Authentication auth, HttpSession session) {
        UUID accountId = accountId(auth);
        Object sessionId = session.getAttribute("activeOpticId");
        if (sessionId instanceof String selected) {
            UUID opticId = UUID.fromString(selected);
            if (isMember(accountId, opticId)) return opticId;
            session.removeAttribute("activeOpticId");
        }
        return jdbc.query("SELECT m.optic_id FROM optic_memberships m JOIN optics o ON o.id=m.optic_id "
                        + "WHERE m.account_id=? AND m.active=true AND o.active=true ORDER BY m.joined_at",
                (rs, n) -> (UUID) rs.getObject(1), accountId).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Não há ótica ativa para esta conta."));
    }

    public boolean isMember(UUID accountId, UUID opticId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM optic_memberships m JOIN optics o ON o.id=m.optic_id "
                + "WHERE m.optic_id=? AND m.account_id=? AND m.active AND o.active",
                Integer.class, opticId, accountId);
        return count != null && count > 0;
    }

    public String role(UUID account, UUID opticId) {
        return jdbc.query("SELECT role FROM optic_memberships WHERE optic_id=? AND account_id=? AND active",
                (rs, n) -> rs.getString(1), opticId, account).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Você não tem acesso a esta ótica."));
    }

    public boolean can(UUID account, UUID opticId, String permission) {
        String role = role(account, opticId);
        if ("MASTER".equals(role)) return true;
        List<String> explicit = jdbc.query("SELECT effect FROM member_permission_overrides "
                + "WHERE optic_id=? AND account_id=? AND permission=?",
                (rs,n)->rs.getString(1), opticId, account, permission);
        if (!explicit.isEmpty()) return "ALLOW".equals(explicit.get(0));
        return switch (role) {
            case "MANAGER" -> !permission.equals("USERS_MANAGE") && !permission.equals("SITE_MANAGE");
            case "SECRETARY" -> List.of("CUSTOMERS_READ","CUSTOMERS_WRITE","SALES_READ",
                        "SALES_DELIVER","VISITS_READ","VISITS_MANAGE","CATALOG_READ","REPORTS_READ",
                        "PATIENTS_READ","PATIENTS_WRITE","APPOINTMENTS_READ","APPOINTMENTS_MANAGE",
                        "PARTNER_CLINICS_MANAGE","RECEIPTS_READ","RECEIPTS_ISSUE").contains(permission);
            case "SELLER" -> List.of("CUSTOMERS_READ","CUSTOMERS_WRITE","SALES_READ",
                        "SALES_CREATE","SALES_DELIVER","VISITS_READ","VISITS_MANAGE",
                        "CATALOG_READ","REPORTS_READ","PATIENTS_READ",
                        "RECEIPTS_READ","RECEIPTS_ISSUE").contains(permission);
            default -> false;
        };
    }

    public void require(Authentication auth, HttpSession session, String permission) {
        UUID account = accountId(auth);
        UUID optic = optic(auth,session);
        if (!can(account,optic,permission)) throw new AccessDeniedException("Você não tem permissão para esta operação.");
        var status = jdbc.queryForMap("SELECT s.billing_mode,s.trial_ends_at,s.payment_active,s.paid_through_at "
                + "FROM optic_subscriptions s WHERE s.optic_id=?", optic);
        String mode = (String) status.get("billing_mode");
        boolean active = switch (mode) {
            case "COMPLIMENTARY" -> true;
            case "TRIAL" -> ((java.sql.Timestamp) status.get("trial_ends_at")).toInstant()
                    .isAfter(java.time.Instant.now());
            case "PAID" -> (Boolean) status.get("payment_active")
                    && status.get("paid_through_at") != null
                    && ((java.sql.Timestamp) status.get("paid_through_at")).toInstant()
                            .isAfter(java.time.Instant.now());
            default -> false;
        };
        if (!active) throw new AccessDeniedException("Assinatura inativa ou teste expirado. Fale com o administrador.");
    }

    public void requireMaster(Authentication auth, HttpSession session) {
        if (!"MASTER".equals(role(accountId(auth),optic(auth,session))))
            throw new AccessDeniedException("Somente um administrador da ótica pode realizar esta operação.");
    }

    public void requirePlatform(Authentication auth) {
        UUID account = accountId(auth);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_operators WHERE account_id=? AND active", Integer.class, account);
        if (count == null || count == 0) throw new AccessDeniedException("Área exclusiva do operador da plataforma.");
    }

    public List<Map<String,Object>> memberships(UUID account) {
        return jdbc.queryForList("SELECT o.id, o.trade_name, o.slug, m.role, m.primary_master, o.site_enabled, o.home_visits_enabled, o.tax_document, "
                + "s.billing_mode, s.trial_ends_at FROM optic_memberships m "
                + "JOIN optics o ON o.id=m.optic_id JOIN optic_subscriptions s ON s.optic_id=o.id "
                + "WHERE m.account_id=? AND m.active AND o.active ORDER BY o.trade_name",account);
    }
}
