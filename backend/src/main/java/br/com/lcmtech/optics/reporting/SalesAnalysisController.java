package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.catalog.domain.CatalogCategory;
import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.OpticClock;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Análise de vendas para o dono: quem mais vende, quais marcas e tipos mais saem, como os clientes pagam e de onde
 * vêm as vendas. Rankings com quantidade, valor, ticket médio, participação e comparação com o mesmo período do mês
 * anterior. Vendas canceladas ficam fora. Aceita os mesmos filtros da consulta de vendas.
 */
@RestController
@RequestMapping("/api/v1/reports/sales-analysis")
public class SalesAnalysisController {
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Dimensão → título, chave de agrupamento, rótulo e filtro da consulta que a linha abre. */
    record Dimension(String title, String column, String key, String label, String filter) {}

    static final Map<String, Dimension> DIMENSIONS = new LinkedHashMap<>();
    static {
        DIMENSIONS.put("seller", new Dimension("Por funcionária", "Funcionária", "s.seller_id::text", "a.full_name", "sellerId"));
        DIMENSIONS.put("frameBrand", new Dimension("Por marca de armação", "Marca da armação", "lower(fr.brand)", "fr.brand", "frameBrand"));
        DIMENSIONS.put("lensBrand", new Dimension("Por marca de lente", "Marca da lente", "lower(l.brand)", "l.brand", "lensBrand"));
        DIMENSIONS.put("lensCategory", new Dimension("Por tipo de lente", "Tipo de lente", "coalesce(l.category,'NONE')",
                "coalesce(l.category,'NONE')", "lensCategory"));
        DIMENSIONS.put("frameCategory", new Dimension("Por categoria de armação", "Categoria da armação",
                "coalesce(fr.category,'NONE')", "coalesce(fr.category,'NONE')", "frameCategory"));
        DIMENSIONS.put("paymentMethod", new Dimension("Por forma de pagamento", "Pagamento", "s.payment_method",
                "s.payment_method", "paymentMethod"));
        DIMENSIONS.put("origin", new Dimension("Por origem", "Origem", SalesQuery.ORIGIN_EXPR, SalesQuery.ORIGIN_EXPR, "origin"));
    }

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticClock clock;

    public SalesAnalysisController(JdbcTemplate jdbc, OpticContext context, OpticClock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    record Line(String key, String filterValue, String label, long count, BigDecimal amount, BigDecimal avgTicket,
                BigDecimal share, BigDecimal previousAmount, long previousCount, BigDecimal variation) {}

    record Analysis(String dimension, String title, String column, String filter, LocalDate from, LocalDate to,
                    LocalDate previousFrom, LocalDate previousTo, long count, BigDecimal amount, BigDecimal avgTicket,
                    long previousCount, BigDecimal previousAmount, BigDecimal variation, List<Line> rows) {}

    @GetMapping
    public Analysis analyze(@RequestParam(defaultValue = "seller") String dimension, DetailedReportController.Filters filters,
                            Authentication auth, HttpSession session) {
        context.require(auth, session, "REPORTS_READ");
        context.require(auth, session, "SALES_READ");
        return compute(context.optic(auth, session), dimension, filters);
    }

    @GetMapping("/export/{format}")
    public ResponseEntity<byte[]> export(@PathVariable String format, @RequestParam(defaultValue = "seller") String dimension,
                                         DetailedReportController.Filters filters, Authentication auth,
                                         HttpSession session) throws IOException {
        context.require(auth, session, "REPORTS_READ");
        context.require(auth, session, "SALES_READ");
        UUID optic = context.optic(auth, session);
        Analysis a = compute(optic, dimension, filters);
        List<String> headers = List.of(a.column(), "Vendas", "Valor vendido", "Ticket médio", "Participação",
                "Mês anterior", "Variação");
        List<List<String>> rows = new ArrayList<>();
        for (Line l : a.rows())
            rows.add(List.of(l.label(), String.valueOf(l.count()), plain(l.amount()), plain(l.avgTicket()),
                    percent(l.share()), plain(l.previousAmount()), l.variation() == null ? "novo" : signed(l.variation())));
        List<String> summary = List.of(
                "Total: " + a.count() + " vendas   |   " + TabularReport.Money.brl(a.amount()) + "   |   Ticket médio: "
                        + (a.avgTicket() == null ? "—" : TabularReport.Money.brl(a.avgTicket())),
                "Mesmo período do mês anterior (" + a.previousFrom().format(BR) + " a " + a.previousTo().format(BR) + "): "
                        + a.previousCount() + " vendas   |   " + TabularReport.Money.brl(a.previousAmount())
                        + (a.variation() == null ? "" : "   |   Variação: " + signed(a.variation())),
                "Vendas canceladas ficam fora.");
        boolean[] money = {false, false, true, true, false, true, false};
        TabularReport t = new TabularReport("Análise de vendas - " + a.title().toLowerCase(), "Análise",
                "optics-analise-" + a.dimension(), headers, rows, summary, money, new int[]{22, 7, 11, 10, 9, 11, 8});
        String shop = jdbc.queryForObject("SELECT trade_name FROM optics WHERE id=?", String.class, optic);
        String period = a.from().format(BR) + " a " + a.to().format(BR);
        return switch (format) {
            case "csv" -> DetailedReportController.file(t.csv(), "text/csv;charset=UTF-8", t.fileBase() + ".csv");
            case "xlsx" -> DetailedReportController.file(t.xlsx(), TabularReport.XLSX, t.fileBase() + ".xlsx");
            case "pdf" -> DetailedReportController.file(t.pdf(shop, period), "application/pdf", t.fileBase() + ".pdf");
            default -> throw new IllegalArgumentException("Formato inválido. Use pdf, xlsx ou csv.");
        };
    }

    Analysis compute(UUID optic, String dimension, DetailedReportController.Filters filters) {
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) throw new IllegalArgumentException("Agrupamento inválido.");
        LocalDate today = clock.today(optic);
        LocalDate from = filters.from() == null ? today.withDayOfMonth(1) : filters.from();
        LocalDate to = filters.to() == null ? today : filters.to();
        if (from.isAfter(to)) throw new IllegalArgumentException("A data inicial deve ser anterior ou igual à data final.");
        if (from.plusYears(2).isBefore(to)) throw new IllegalArgumentException("Escolha um período de até 2 anos.");
        LocalDate previousFrom = from.minusMonths(1), previousTo = to.minusMonths(1);
        SalesQuery.SalesFilter base = filters.sales();
        base.validate();

        Map<String, Object[]> current = group(optic, d, withPeriod(base, from, to));
        Map<String, Object[]> previous = group(optic, d, withPeriod(base, previousFrom, previousTo));
        long count = current.values().stream().mapToLong(v -> (Long) v[1]).sum();
        BigDecimal amount = current.values().stream().map(v -> (BigDecimal) v[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        long previousCount = previous.values().stream().mapToLong(v -> (Long) v[1]).sum();
        BigDecimal previousAmount = previous.values().stream().map(v -> (BigDecimal) v[2]).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Line> rows = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : current.entrySet()) {
            Object[] v = e.getValue(), before = previous.get(e.getKey());
            long n = (Long) v[1];
            BigDecimal value = (BigDecimal) v[2];
            BigDecimal prev = before == null ? BigDecimal.ZERO : (BigDecimal) before[2];
            String raw = (String) v[0];
            rows.add(new Line(e.getKey(), filterValue(dimension, e.getKey(), raw), label(dimension, raw), n, value,
                    divide(value, n), share(value, amount), prev, before == null ? 0 : (Long) before[1], variation(value, prev)));
        }
        rows.sort((x, y) -> y.amount().compareTo(x.amount()) != 0 ? y.amount().compareTo(x.amount())
                : x.label().compareToIgnoreCase(y.label()));
        return new Analysis(dimension, d.title(), d.column(), d.filter(), from, to, previousFrom, previousTo, count,
                amount, divide(amount, count), previousCount, previousAmount, variation(amount, previousAmount), rows);
    }

    /** chave → [rótulo, quantidade, valor]. */
    private Map<String, Object[]> group(UUID optic, Dimension d, SalesQuery.SalesFilter filter) {
        SalesQuery.Where where = SalesQuery.where(optic, filter);
        Map<String, Object[]> out = new HashMap<>();
        jdbc.query("SELECT k, min(lbl), count(*), coalesce(sum(amount),0) FROM (SELECT " + d.key() + " AS k, "
                        + d.label() + " AS lbl, s.gross_amount AS amount" + SalesQuery.FROM + where.sql()
                        + " AND s.status<>'CANCELLED') t GROUP BY k",
                rs -> { out.put(rs.getString(1), new Object[]{rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)}); },
                where.with());
        return out;
    }

    private static SalesQuery.SalesFilter withPeriod(SalesQuery.SalesFilter f, LocalDate from, LocalDate to) {
        return new SalesQuery.SalesFilter(from, to, f.status(), f.sellerId(), f.frameBrand(), f.frameCategory(),
                f.lensBrand(), f.lensCategory(), f.paymentMethod(), f.origin(), f.customer());
    }

    private static String filterValue(String dimension, String key, String label) {
        return switch (dimension) {
            case "frameBrand", "lensBrand" -> label;
            default -> key;
        };
    }

    private static String label(String dimension, String raw) {
        return switch (dimension) {
            case "lensCategory" -> "NONE".equals(raw) ? CatalogCategory.NOT_INFORMED
                    : SalesQuery.shortLabel(CatalogCategory.label("LENS", raw));
            case "frameCategory" -> "NONE".equals(raw) ? "Não informada" : SalesQuery.shortLabel(CatalogCategory.label("FRAME", raw));
            case "paymentMethod" -> DetailedReportController.PAYMENT.getOrDefault(raw, raw);
            case "origin" -> SalesQuery.ORIGIN.getOrDefault(raw, raw);
            default -> raw;
        };
    }

    private static BigDecimal divide(BigDecimal value, long n) {
        return n == 0 ? null : value.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO
                : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Variação percentual; null quando não havia vendas no período anterior. */
    static BigDecimal variation(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) return null;
        return now.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 1, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal v) {
        return v.toPlainString().replace('.', ',') + "%";
    }

    private static String signed(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + percent(v);
    }
}
