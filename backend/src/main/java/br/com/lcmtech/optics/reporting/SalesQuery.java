package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.catalog.domain.CatalogCategory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta de vendas com filtros combináveis, usada pela consulta detalhada e pela análise de vendas (rankings).
 * Só os filtros preenchidos entram no WHERE; o optic_id vem sempre da sessão.
 */
final class SalesQuery {
    static final Map<String, String> ORIGIN = labels(
            "DIRETA", "Venda direta", "CONSULTA", "Paciente de consulta", "VISITA", "Visita em domicílio");

    /** Junções com cliente, vendedora, lente e armação (aliases s, c, a, l, fr). */
    static final String FROM = """
             FROM sales s
            JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
            JOIN accounts a ON a.id=s.seller_id
            JOIN catalog_items l ON l.optic_id=s.optic_id AND l.id=s.lens_id
            JOIN catalog_items fr ON fr.optic_id=s.optic_id AND fr.id=s.frame_id
            """;

    /** Origem da venda: visita em domicílio convertida, paciente de consulta ou venda direta. */
    static final String ORIGIN_EXPR = """
            CASE WHEN EXISTS (SELECT 1 FROM home_visits v WHERE v.optic_id=s.optic_id AND v.sale_id=s.id) THEN 'VISITA'
                 WHEN c.source_patient_id IS NOT NULL THEN 'CONSULTA' ELSE 'DIRETA' END""";

    private SalesQuery() {}

    /** Filtros da consulta de vendas. Textos vazios contam como "todos". */
    record SalesFilter(LocalDate from, LocalDate to, String status, UUID sellerId, String frameBrand,
                       String frameCategory, String lensBrand, String lensCategory, String paymentMethod,
                       String origin, String customer) {
        SalesFilter {
            status = blank(status);
            frameBrand = blank(frameBrand);
            frameCategory = blank(frameCategory);
            lensBrand = blank(lensBrand);
            lensCategory = blank(lensCategory);
            paymentMethod = blank(paymentMethod);
            origin = blank(origin);
            customer = blank(customer);
        }

        void validate() {
            if (from != null && to != null && from.isAfter(to))
                throw new IllegalArgumentException("A data inicial deve ser anterior ou igual à data final.");
            check(status, DetailedReportController.SALE_STATUS, "Situação inválida para este relatório.");
            check(paymentMethod, DetailedReportController.PAYMENT, "Forma de pagamento inválida.");
            check(origin, ORIGIN, "Origem inválida.");
            if (lensCategory != null && !"NONE".equals(lensCategory)) CatalogCategory.require("LENS", lensCategory);
            if (frameCategory != null && !"NONE".equals(frameCategory)) CatalogCategory.require("FRAME", frameCategory);
            if (customer != null && customer.length() > 120) throw new IllegalArgumentException("Busca de cliente muito longa.");
        }

        private static void check(String value, Map<String, String> allowed, String message) {
            if (value != null && !allowed.containsKey(value)) throw new IllegalArgumentException(message);
        }
    }

    record Where(String sql, List<Object> params) {
        Object[] with(Object... extra) {
            List<Object> all = new ArrayList<>(params);
            all.addAll(List.of(extra));
            return all.toArray();
        }
    }

    /** Monta " WHERE ..." com os filtros preenchidos. Categoria "NONE" = itens sem tipo informado. */
    static Where where(UUID optic, SalesFilter f) {
        StringBuilder sql = new StringBuilder(" WHERE s.optic_id=?");
        List<Object> p = new ArrayList<>(List.of(optic));
        if (f.from() != null) { sql.append(" AND s.sold_on>=?"); p.add(java.sql.Date.valueOf(f.from())); }
        if (f.to() != null) { sql.append(" AND s.sold_on<=?"); p.add(java.sql.Date.valueOf(f.to())); }
        if (f.status() != null) { sql.append(" AND s.status=?"); p.add(f.status()); }
        if (f.sellerId() != null) { sql.append(" AND s.seller_id=?"); p.add(f.sellerId()); }
        if (f.paymentMethod() != null) { sql.append(" AND s.payment_method=?"); p.add(f.paymentMethod()); }
        if (f.frameBrand() != null) { sql.append(" AND lower(fr.brand)=lower(?)"); p.add(f.frameBrand()); }
        if (f.lensBrand() != null) { sql.append(" AND lower(l.brand)=lower(?)"); p.add(f.lensBrand()); }
        category(sql, p, "fr", f.frameCategory());
        category(sql, p, "l", f.lensCategory());
        if (f.origin() != null) { sql.append(" AND (").append(ORIGIN_EXPR).append(")=?"); p.add(f.origin()); }
        if (f.customer() != null) {
            String digits = f.customer().replaceAll("\\D", "");
            String like = "%" + f.customer().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            if (digits.length() >= 3) {
                sql.append(" AND (lower(c.full_name) LIKE ? OR c.cpf LIKE ?)");
                p.add(like);
                p.add("%" + digits + "%");
            } else {
                sql.append(" AND lower(c.full_name) LIKE ?");
                p.add(like);
            }
        }
        return new Where(sql.toString(), p);
    }

    private static void category(StringBuilder sql, List<Object> p, String alias, String value) {
        if (value == null) return;
        if ("NONE".equals(value)) sql.append(" AND ").append(alias).append(".category IS NULL");
        else { sql.append(" AND ").append(alias).append(".category=?"); p.add(value); }
    }

    static String lensLabel(Object brand, Object category) {
        return brand + (category == null ? "" : " · " + shortLabel(CatalogCategory.label("LENS", category.toString())));
    }

    static String frameLabel(Object brand, Object category) {
        return brand + (category == null ? "" : " · " + shortLabel(CatalogCategory.label("FRAME", category.toString())));
    }

    /** "Multifocal (progressiva)" vira "Multifocal" nas tabelas. */
    static String shortLabel(String label) {
        return label.replaceAll(" \\(.*\\)$", "");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
