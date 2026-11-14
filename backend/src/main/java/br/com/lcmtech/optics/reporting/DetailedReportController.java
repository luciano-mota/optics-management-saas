package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.OpticClock;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios detalhados de vendas, consultas, recibos e pacientes, com os mesmos filtros na prévia e nas
 * exportações (CSV, XLSX e PDF). Paciente nunca conta como venda: a conversão de uma consulta só existe quando o
 * paciente virou cliente e tem venda não cancelada a partir da data da consulta.
 */
@RestController
@RequestMapping("/api/v1/reports/detailed")
public class DetailedReportController {
    static final int MAX_ROWS = 5000;
    private static final int PREVIEW_ROWS = 50;
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final Map<String, String> SALE_STATUS = labels(
            "AWAITING_PRODUCTION", "Aguardando produção", "READY_FOR_DELIVERY", "Pronto para entrega",
            "DELIVERED", "Entregue", "CANCELLED", "Cancelado");
    static final Map<String, String> PAYMENT = labels(
            "DINHEIRO", "Dinheiro", "PIX", "Pix", "DEBITO", "Débito", "CREDITO", "Crédito");
    static final Map<String, String> APPOINTMENT_STATUS = labels(
            "SCHEDULED", "Agendado", "CONFIRMED", "Confirmado", "COMPLETED", "Consulta realizada",
            "NO_SHOW", "Não compareceu", "CANCELLED", "Cancelado");
    static final Map<String, String> RECEIPT_STATUS = labels("VALID", "Válido", "REVERSED", "Estornado");

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticClock clock;

    public DetailedReportController(JdbcTemplate jdbc, OpticContext context, OpticClock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    /** Filtros da query string; os que não se aplicam ao relatório são ignorados. */
    public record Filters(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          String status, UUID clinicId, UUID sellerId, String frameBrand, String frameCategory,
                          String lensBrand, String lensCategory, String paymentMethod, String origin, String customer) {
        public Filters {
            if (status != null && status.isBlank()) status = null;
        }

        public static Filters period(LocalDate from, LocalDate to) {
            return new Filters(from, to, null, null, null, null, null, null, null, null, null, null);
        }

        void validate() {
            if (from != null && to != null && from.isAfter(to))
                throw new IllegalArgumentException("A data inicial deve ser anterior ou igual à data final.");
        }

        SalesQuery.SalesFilter sales() {
            return new SalesQuery.SalesFilter(from, to, status, sellerId, frameBrand, frameCategory, lensBrand,
                    lensCategory, paymentMethod, origin, customer);
        }

        String period() {
            if (from == null && to == null) return "Todo o período";
            if (from == null) return "Até " + to.format(BR);
            if (to == null) return "A partir de " + from.format(BR);
            return from.format(BR) + " a " + to.format(BR);
        }
    }

    /** Resultado completo: a tabela exportável e o resumo por grupo mostrado na tela. */
    record Result(TabularReport table, List<Map<String, Object>> groups) {}

    @GetMapping("/{kind}")
    public Map<String, Object> preview(@PathVariable String kind, Filters filters,
                                       Authentication auth, HttpSession session) {
        Result result = build(kind, filters, auth, session);
        TabularReport t = result.table();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", t.title());
        body.put("headers", t.headers());
        body.put("money", t.money());
        body.put("rows", t.rows().subList(0, Math.min(PREVIEW_ROWS, t.rows().size())));
        body.put("totalRows", t.rows().size());
        body.put("truncated", t.rows().size() >= MAX_ROWS);
        body.put("summary", t.summary());
        body.put("groups", result.groups());
        return body;
    }

    /** Opções dos filtros da consulta de vendas (vendedoras, marcas e listas fechadas) da ótica da sessão. */
    @GetMapping("/vendas/options")
    public Map<String, Object> salesOptions(Authentication auth, HttpSession session) {
        context.require(auth, session, "REPORTS_READ");
        context.require(auth, session, "SALES_READ");
        UUID optic = context.optic(auth, session);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellers", jdbc.queryForList("""
                SELECT a.id, a.full_name AS name FROM optic_memberships m JOIN accounts a ON a.id=m.account_id
                WHERE m.optic_id=? AND (m.active OR EXISTS (SELECT 1 FROM sales s WHERE s.optic_id=m.optic_id
                      AND s.seller_id=m.account_id))
                ORDER BY a.full_name""", optic));
        body.put("lensBrands", jdbc.queryForList("SELECT DISTINCT brand FROM catalog_items WHERE optic_id=? AND kind='LENS' "
                + "ORDER BY brand", String.class, optic));
        body.put("frameBrands", jdbc.queryForList("SELECT DISTINCT brand FROM catalog_items WHERE optic_id=? AND kind='FRAME' "
                + "ORDER BY brand", String.class, optic));
        body.put("lensCategories", br.com.lcmtech.optics.catalog.domain.CatalogCategory.LENS);
        body.put("frameCategories", br.com.lcmtech.optics.catalog.domain.CatalogCategory.FRAME);
        body.put("payments", PAYMENT);
        body.put("statuses", SALE_STATUS);
        body.put("origins", SalesQuery.ORIGIN);
        return body;
    }

    @GetMapping("/{kind}/export/{format}")
    public ResponseEntity<byte[]> export(@PathVariable String kind, @PathVariable String format, Filters filters,
                                         Authentication auth, HttpSession session) throws IOException {
        TabularReport t = build(kind, filters, auth, session).table();
        UUID optic = context.optic(auth, session);
        return switch (format) {
            case "csv" -> file(t.csv(), "text/csv;charset=UTF-8", t.fileBase() + ".csv");
            case "xlsx" -> file(t.xlsx(), TabularReport.XLSX, t.fileBase() + ".xlsx");
            case "pdf" -> file(t.pdf(shopName(optic), filters.period()), "application/pdf", t.fileBase() + ".pdf");
            default -> throw new IllegalArgumentException("Formato inválido. Use pdf, xlsx ou csv.");
        };
    }

    Result build(String kind, Filters f, Authentication auth, HttpSession session) {
        context.require(auth, session, "REPORTS_READ");
        f.validate();
        UUID optic = context.optic(auth, session);
        return switch (kind) {
            case "vendas" -> {
                context.require(auth, session, "SALES_READ");
                yield sales(optic, f, context.can(context.accountId(auth), optic, "FINANCE_READ"));
            }
            case "consultas" -> {
                context.require(auth, session, "APPOINTMENTS_READ");
                yield appointments(optic, f);
            }
            case "recibos" -> {
                context.require(auth, session, "RECEIPTS_READ");
                yield receipts(optic, f);
            }
            case "pacientes" -> {
                context.require(auth, session, "PATIENTS_READ");
                yield patients(optic, f);
            }
            default -> throw new java.util.NoSuchElementException("Relatório não encontrado.");
        };
    }

    // ---------------------------------------------------------------- vendas

    /**
     * Consulta de vendas com os filtros do dono (vendedora, marcas, tipos, pagamento, origem, cliente).
     * Taxas, líquido e totais em dinheiro só para quem vê o financeiro.
     */
    Result sales(UUID optic, Filters f, boolean finance) {
        SalesQuery.SalesFilter filter = f.sales();
        filter.validate();
        SalesQuery.Where where = SalesQuery.where(optic, filter);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT s.sold_on, c.full_name AS customer, a.full_name AS seller, l.brand AS lens, l.category AS lens_category,"
                + " fr.brand AS frame, fr.category AS frame_category, s.status, s.payment_method, s.installments,"
                + " s.charged_amount, s.fee_amount, s.net_amount, " + SalesQuery.ORIGIN_EXPR + " AS origin"
                + SalesQuery.FROM + where.sql() + " ORDER BY s.sold_on DESC, s.created_at DESC LIMIT ?",
                where.with(MAX_ROWS));
        List<String> headers = new ArrayList<>(List.of("Data", "Cliente", "Vendedor", "Lente", "Armação", "Situação",
                "Pagamento", "Parcelas", "Origem", "Valor cobrado"));
        if (finance) headers.addAll(List.of("Taxas", "Líquido"));
        List<List<String>> rows = new ArrayList<>();
        BigDecimal charged = BigDecimal.ZERO, fees = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int valid = 0, cancelled = 0;
        Map<String, Map<String, Object>> bySeller = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            boolean isCancelled = "CANCELLED".equals(r.get("status"));
            List<String> row = new ArrayList<>(List.of(date(r.get("sold_on")), str(r.get("customer")),
                    str(r.get("seller")), SalesQuery.lensLabel(r.get("lens"), r.get("lens_category")),
                    SalesQuery.frameLabel(r.get("frame"), r.get("frame_category")),
                    SALE_STATUS.getOrDefault(str(r.get("status")), str(r.get("status"))),
                    PAYMENT.getOrDefault(str(r.get("payment_method")), str(r.get("payment_method"))),
                    str(r.get("installments")), SalesQuery.ORIGIN.getOrDefault(str(r.get("origin")), ""),
                    money(r.get("charged_amount"))));
            if (finance) {
                row.add(money(r.get("fee_amount")));
                row.add(money(r.get("net_amount")));
            }
            rows.add(row);
            Map<String, Object> seller = bySeller.computeIfAbsent(str(r.get("seller")), k -> group("Vendedor", k));
            if (isCancelled) {
                cancelled++;
                increment(seller, "Canceladas");
                continue;
            }
            valid++;
            increment(seller, "Vendas");
            charged = charged.add((BigDecimal) r.get("charged_amount"));
            fees = fees.add((BigDecimal) r.get("fee_amount"));
            net = net.add((BigDecimal) r.get("net_amount"));
            if (finance) add(seller, "Valor cobrado", (BigDecimal) r.get("charged_amount"));
        }
        List<String> summary = new ArrayList<>();
        summary.add("Vendas válidas: " + valid + "   |   Canceladas: " + cancelled);
        if (finance) {
            summary.add("Valor cobrado: " + TabularReport.Money.brl(charged) + "   |   Ticket médio: "
                    + (valid == 0 ? "—" : TabularReport.Money.brl(charged.divide(BigDecimal.valueOf(valid), 2,
                    java.math.RoundingMode.HALF_UP))) + "   |   Taxas: " + TabularReport.Money.brl(fees)
                    + "   |   Líquido: " + TabularReport.Money.brl(net) + "   (canceladas fora dos totais)");
        }
        String filtersText = describe(optic, filter);
        if (!filtersText.isEmpty()) summary.add("Filtros: " + filtersText);
        return new Result(new TabularReport("Relatório de vendas", "Vendas", "optics-vendas", headers, rows, summary,
                moneyColumns(headers, "Valor cobrado", "Taxas", "Líquido"),
                widths(headers.size(), 7, 14, 11, 13, 13, 10, 7, 5, 10, 8, 7, 7)),
                new ArrayList<>(bySeller.values()));
    }

    /** Filtros aplicados, por extenso, para o rodapé do PDF e da planilha (auditoria). */
    private String describe(UUID optic, SalesQuery.SalesFilter f) {
        List<String> parts = new ArrayList<>();
        // O nome só vem de quem é (ou foi) da equipe desta ótica: um ID de outra ótica não revela nada.
        if (f.sellerId() != null) parts.add("vendedor(a) " + jdbc.queryForList(
                "SELECT a.full_name FROM accounts a JOIN optic_memberships m ON m.account_id=a.id "
                        + "WHERE a.id=? AND m.optic_id=?", String.class, f.sellerId(), optic)
                .stream().findFirst().orElse("não encontrado(a) nesta ótica"));
        if (f.frameBrand() != null) parts.add("armação " + f.frameBrand());
        if (f.frameCategory() != null) parts.add("categoria " + ("NONE".equals(f.frameCategory()) ? "não informada"
                : SalesQuery.shortLabel(br.com.lcmtech.optics.catalog.domain.CatalogCategory.label("FRAME", f.frameCategory()))));
        if (f.lensBrand() != null) parts.add("lente " + f.lensBrand());
        if (f.lensCategory() != null) parts.add("tipo de lente " + ("NONE".equals(f.lensCategory()) ? "não informado"
                : SalesQuery.shortLabel(br.com.lcmtech.optics.catalog.domain.CatalogCategory.label("LENS", f.lensCategory()))));
        if (f.paymentMethod() != null) parts.add("pagamento " + PAYMENT.get(f.paymentMethod()));
        if (f.status() != null) parts.add("situação " + SALE_STATUS.get(f.status()));
        if (f.origin() != null) parts.add("origem " + SalesQuery.ORIGIN.get(f.origin()));
        if (f.customer() != null) parts.add("cliente \"" + f.customer() + "\"");
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- consultas

    /** Consultas por clínica parceira, situação e conversão em venda. */
    Result appointments(UUID optic, Filters f) {
        requireStatus(f.status(), APPOINTMENT_STATUS);
        String zone = clock.zone(optic).getId();
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT (a.starts_at AT TIME ZONE ?) AS local_start, p.full_name AS patient, k.name AS clinic, a.status,
                       EXISTS (SELECT 1 FROM customers c JOIN sales s ON s.optic_id=c.optic_id AND s.customer_id=c.id
                               WHERE c.optic_id=a.optic_id AND c.source_patient_id=a.patient_id
                                 AND s.status<>'CANCELLED' AND s.sold_on>=(a.starts_at AT TIME ZONE ?)::date) AS converted
                FROM optical_appointments a
                JOIN optometry_patients p ON p.optic_id=a.optic_id AND p.id=a.patient_id
                JOIN optical_partner_clinics k ON k.optic_id=a.optic_id AND k.id=a.clinic_id
                WHERE a.optic_id=?
                  AND (?::date IS NULL OR (a.starts_at AT TIME ZONE ?)::date>=?::date)
                  AND (?::date IS NULL OR (a.starts_at AT TIME ZONE ?)::date<=?::date)
                  AND (?::text IS NULL OR a.status=?::text) AND (?::uuid IS NULL OR a.clinic_id=?::uuid)
                ORDER BY a.starts_at DESC LIMIT ?
                """, zone, zone, optic, f.from(), zone, f.from(), f.to(), zone, f.to(), f.status(), f.status(),
                f.clinicId(), f.clinicId(), MAX_ROWS);
        List<String> headers = List.of("Data", "Horário", "Paciente", "Clínica", "Situação", "Convertida em venda");
        List<List<String>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> byClinic = new LinkedHashMap<>();
        int completed = 0, converted = 0, noShow = 0, cancelled = 0;
        for (Map<String, Object> r : records) {
            java.time.LocalDateTime start = ((java.sql.Timestamp) r.get("local_start")).toLocalDateTime();
            String state = str(r.get("status"));
            boolean conv = Boolean.TRUE.equals(r.get("converted"));
            rows.add(List.of(start.toLocalDate().format(BR), start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), str(r.get("patient")),
                    str(r.get("clinic")), APPOINTMENT_STATUS.getOrDefault(state, state), conv ? "Sim" : "Não"));
            Map<String, Object> clinic = byClinic.computeIfAbsent(str(r.get("clinic")), k -> {
                Map<String, Object> g = group("Clínica", k);
                g.put("Consultas", 0); g.put("Realizadas", 0); g.put("Não compareceu", 0);
                g.put("Canceladas", 0); g.put("Convertidas", 0);
                return g;
            });
            increment(clinic, "Consultas");
            switch (state) {
                case "COMPLETED" -> { completed++; increment(clinic, "Realizadas"); }
                case "NO_SHOW" -> { noShow++; increment(clinic, "Não compareceu"); }
                case "CANCELLED" -> { cancelled++; increment(clinic, "Canceladas"); }
                default -> { }
            }
            if (conv) { converted++; increment(clinic, "Convertidas"); }
        }
        for (Map<String, Object> clinic : byClinic.values())
            clinic.put("Conversão", rate((Integer) clinic.get("Convertidas"), (Integer) clinic.get("Realizadas")));
        List<String> summary = List.of(
                "Consultas: " + records.size() + "   |   Realizadas: " + completed + "   |   Não compareceu: " + noShow
                        + "   |   Canceladas: " + cancelled,
                "Convertidas em venda: " + converted + "   |   Conversão sobre as realizadas: " + rate(converted, completed));
        return new Result(new TabularReport("Relatório de consultas", "Consultas", "optics-consultas", headers, rows,
                summary, moneyColumns(headers), new int[]{8, 6, 22, 20, 12, 10}), new ArrayList<>(byClinic.values()));
    }

    // ---------------------------------------------------------------- recibos

    /** Recibos emitidos no período (pela data de emissão no fuso da ótica); estornado quando o pagamento foi estornado. */
    Result receipts(UUID optic, Filters f) {
        requireStatus(f.status(), RECEIPT_STATUS);
        String zone = clock.zone(optic).getId();
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT r.receipt_number, (r.issued_at AT TIME ZONE ?)::date AS issued_on, r.paid_on,
                       coalesce(r.snapshot->>'customer', c.full_name) AS customer, r.payment_gross,
                       a.full_name AS issuer, (e.reversed_at IS NOT NULL OR st.reversed_at IS NOT NULL) AS reversed,
                       coalesce(st.reversed_on, (e.reversed_at AT TIME ZONE ?)::date) AS reversed_on
                FROM sale_receipts r
                JOIN finance_entries e ON e.optic_id=r.optic_id AND e.id=r.finance_entry_id
                LEFT JOIN finance_settlements st ON st.optic_id=r.optic_id AND st.id=r.settlement_id
                JOIN sales s ON s.optic_id=r.optic_id AND s.id=r.sale_id
                JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                JOIN accounts a ON a.id=r.issued_by
                WHERE r.optic_id=?
                  AND (?::date IS NULL OR (r.issued_at AT TIME ZONE ?)::date>=?::date)
                  AND (?::date IS NULL OR (r.issued_at AT TIME ZONE ?)::date<=?::date)
                  AND (?::text IS NULL OR (?::text='REVERSED')=(e.reversed_at IS NOT NULL OR st.reversed_at IS NOT NULL))
                ORDER BY r.receipt_number DESC LIMIT ?
                """, zone, zone, optic, f.from(), zone, f.from(), f.to(), zone, f.to(), f.status(), f.status(),
                MAX_ROWS);
        List<String> headers = List.of("Número", "Emitido em", "Pago em", "Cliente", "Valor", "Situação",
                "Estornado em", "Emitido por");
        List<List<String>> rows = new ArrayList<>();
        BigDecimal validTotal = BigDecimal.ZERO, reversedTotal = BigDecimal.ZERO;
        int valid = 0, reversed = 0;
        for (Map<String, Object> r : records) {
            boolean isReversed = Boolean.TRUE.equals(r.get("reversed"));
            BigDecimal value = (BigDecimal) r.get("payment_gross");
            rows.add(List.of(String.format("%06d", ((Number) r.get("receipt_number")).longValue()),
                    date(r.get("issued_on")), date(r.get("paid_on")), str(r.get("customer")), money(value),
                    RECEIPT_STATUS.get(isReversed ? "REVERSED" : "VALID"),
                    isReversed ? date(r.get("reversed_on")) : "", str(r.get("issuer"))));
            if (isReversed) { reversed++; reversedTotal = reversedTotal.add(value); }
            else { valid++; validTotal = validTotal.add(value); }
        }
        List<String> summary = List.of(
                "Recibos emitidos: " + records.size() + "   |   Válidos: " + valid + " ("
                        + TabularReport.Money.brl(validTotal) + ")   |   Estornados: " + reversed + " ("
                        + TabularReport.Money.brl(reversedTotal) + ")");
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> v = group("Situação", "Válidos");
        v.put("Recibos", valid); v.put("Valor", validTotal);
        Map<String, Object> e = group("Situação", "Estornados");
        e.put("Recibos", reversed); e.put("Valor", reversedTotal);
        groups.add(v); groups.add(e);
        return new Result(new TabularReport("Relatório de recibos", "Recibos", "optics-recibos", headers, rows, summary,
                moneyColumns(headers, "Valor"), new int[]{6, 8, 8, 22, 9, 8, 8, 16}), groups);
    }

    // ---------------------------------------------------------------- pacientes

    /**
     * Pacientes das consultas (sem CPF, telefone ou endereço: dado mínimo para gestão). Com período, entram os
     * pacientes com consulta no período; com clínica, os atendidos por ela.
     */
    Result patients(UUID optic, Filters f) {
        String zone = clock.zone(optic).getId();
        List<Map<String, Object>> records = jdbc.queryForList("""
                WITH filtered AS (
                  SELECT a.* FROM optical_appointments a
                  WHERE a.optic_id=?
                    AND (?::date IS NULL OR (a.starts_at AT TIME ZONE ?)::date>=?::date)
                    AND (?::date IS NULL OR (a.starts_at AT TIME ZONE ?)::date<=?::date)
                    AND (?::uuid IS NULL OR a.clinic_id=?::uuid))
                SELECT p.full_name, (p.created_at AT TIME ZONE ?)::date AS created_on,
                       count(fa.id) AS appointments,
                       count(fa.id) FILTER (WHERE fa.status='COMPLETED') AS completed,
                       (max(fa.starts_at) AT TIME ZONE ?)::date AS last_appointment,
                       (SELECT k.name FROM filtered l JOIN optical_partner_clinics k
                          ON k.optic_id=l.optic_id AND k.id=l.clinic_id
                        WHERE l.patient_id=p.id ORDER BY l.starts_at DESC LIMIT 1) AS last_clinic,
                       EXISTS (SELECT 1 FROM customers c JOIN sales s ON s.optic_id=c.optic_id AND s.customer_id=c.id
                               WHERE c.optic_id=p.optic_id AND c.source_patient_id=p.id
                                 AND s.status<>'CANCELLED') AS customer
                FROM optometry_patients p
                LEFT JOIN filtered fa ON fa.patient_id=p.id
                WHERE p.optic_id=?
                GROUP BY p.optic_id, p.id, p.full_name, p.created_at
                HAVING (?::date IS NULL AND ?::date IS NULL AND ?::uuid IS NULL) OR count(fa.id)>0
                ORDER BY p.full_name LIMIT ?
                """, optic, f.from(), zone, f.from(), f.to(), zone, f.to(), f.clinicId(), f.clinicId(), zone, zone,
                optic, f.from(), f.to(), f.clinicId(), MAX_ROWS);
        List<String> headers = List.of("Paciente", "Cadastrado em", "Consultas", "Realizadas", "Última consulta",
                "Clínica da última consulta", "Comprou na ótica");
        List<List<String>> rows = new ArrayList<>();
        int buyers = 0, withAppointment = 0;
        for (Map<String, Object> r : records) {
            boolean buyer = Boolean.TRUE.equals(r.get("customer"));
            if (buyer) buyers++;
            if (((Number) r.get("appointments")).intValue() > 0) withAppointment++;
            rows.add(List.of(str(r.get("full_name")), date(r.get("created_on")), str(r.get("appointments")),
                    str(r.get("completed")), date(r.get("last_appointment")), str(r.get("last_clinic")),
                    buyer ? "Sim" : "Não"));
        }
        List<String> summary = List.of("Pacientes: " + records.size() + "   |   Com consulta: " + withAppointment
                + "   |   Compraram na ótica: " + buyers + " (" + rate(buyers, records.size()) + ")");
        return new Result(new TabularReport("Relatório de pacientes", "Pacientes", "optics-pacientes", headers, rows,
                summary, moneyColumns(headers), new int[]{24, 9, 7, 7, 9, 20, 8}), List.of());
    }

    // ---------------------------------------------------------------- utilidades

    private String shopName(UUID optic) {
        return jdbc.queryForObject("SELECT trade_name FROM optics WHERE id=?", String.class, optic);
    }

    private static void requireStatus(String status, Map<String, String> allowed) {
        if (status != null && !allowed.containsKey(status))
            throw new IllegalArgumentException("Situação inválida para este relatório.");
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> group(String label, String name) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put(label, name);
        return g;
    }

    private static void increment(Map<String, Object> g, String key) {
        g.merge(key, 1, (a, b) -> (Integer) a + (Integer) b);
    }

    private static void add(Map<String, Object> g, String key, BigDecimal value) {
        g.merge(key, value, (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
    }

    static String rate(int part, int whole) {
        if (whole == 0) return "—";
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString().replace('.', ',') + "%";
    }

    private static boolean[] moneyColumns(List<String> headers, String... names) {
        boolean[] flags = new boolean[headers.size()];
        for (int i = 0; i < headers.size(); i++) flags[i] = List.of(names).contains(headers.get(i));
        return flags;
    }

    private static int[] widths(int size, int... all) {
        return java.util.Arrays.copyOf(all, size);
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String money(Object value) {
        return value == null ? "" : ((BigDecimal) value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String date(Object value) {
        if (value == null) return "";
        LocalDate d = value instanceof java.sql.Date sql ? sql.toLocalDate() : LocalDate.parse(value.toString());
        return d.format(BR);
    }

    static ResponseEntity<byte[]> file(byte[] content, String mime, String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=" + name)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Type", mime).body(content);
    }
}
