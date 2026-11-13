package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.calendar.BusinessCalendarController;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * O "individual" de cada indicador de Vendas Atualizadas: a lista que forma o número. Usa os mesmos fragmentos
 * de SQL do resumo ({@link MonthlySalesSummaryService#VALID_SALE} e {@code VALID_RECEIVABLE}), então a soma do
 * detalhe é sempre igual ao indicador.
 */
@Service
public class IndicatorDetailService {
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale PT = Locale.forLanguageTag("pt-BR");

    /** Detalhes disponíveis e o título de cada um. */
    public static final Map<String, String> TITLES = Map.of(
            "vendas", "Vendas do mês",
            "recebidos", "Vendas líquidas (recebidas)",
            "a-receber", "Vendas a receber",
            "taxas", "Taxas bancárias",
            "dias", "Dias de funcionamento",
            "metas", "Meta x realizado por dia");

    /** Indicador → detalhe que explica o número. */
    static String detailOf(String indicator) {
        return switch (indicator) {
            case "netReceived" -> "recebidos";
            case "toReceive" -> "a-receber";
            case "totalSales", "salesCount", "glassesAndLensesGross" -> "vendas";
            case "bankFees" -> "taxas";
            case "totalOpenDays", "workedDays", "remainingDays" -> "dias";
            default -> "metas";
        };
    }

    private final JdbcTemplate jdbc;
    private final MonthlySalesSummaryService engine;

    public IndicatorDetailService(JdbcTemplate jdbc, MonthlySalesSummaryService engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    public TabularReport detail(UUID optic, int year, int month, LocalDate asOf, String kind) {
        if (!TITLES.containsKey(kind)) throw new java.util.NoSuchElementException("Detalhe não encontrado.");
        MonthlySalesSummaryService.Window w = engine.window(optic, year, month, asOf);
        return switch (kind) {
            case "vendas" -> sales(w);
            case "recebidos" -> receivables(w, true);
            case "a-receber" -> receivables(w, false);
            case "taxas" -> fees(w);
            case "dias" -> days(w);
            default -> goals(w);
        };
    }

    private String file(MonthlySalesSummaryService.Window w, String kind) {
        return "optics-" + kind + "-" + w.year() + "-" + String.format("%02d", w.month()) + "-" + w.asOf();
    }

    // ------------------------------------------------------------ vendas

    private TabularReport sales(MonthlySalesSummaryService.Window w) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.sold_on, c.full_name AS customer, a.full_name AS seller, l.brand AS lens, f.brand AS frame,
                       s.payment_method, s.installments, s.gross_amount, s.fee_amount, s.charged_amount
                FROM sales s
                JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                JOIN accounts a ON a.id=s.seller_id
                JOIN catalog_items l ON l.optic_id=s.optic_id AND l.id=s.lens_id
                JOIN catalog_items f ON f.optic_id=s.optic_id AND f.id=s.frame_id
                WHERE """ + MonthlySalesSummaryService.VALID_SALE + " ORDER BY s.sold_on, s.created_at",
                w.saleParams());
        List<String> headers = List.of("Data", "Cliente", "Vendedor(a)", "Lente", "Armação", "Pagamento", "Parcelas",
                "Valor da venda", "Taxa", "Valor cobrado");
        List<List<String>> out = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            gross = gross.add((BigDecimal) r.get("gross_amount"));
            out.add(List.of(date(r.get("sold_on")), str(r.get("customer")), str(r.get("seller")), str(r.get("lens")),
                    str(r.get("frame")), payment(r.get("payment_method")), str(r.get("installments")),
                    money(r.get("gross_amount")), money(r.get("fee_amount")), money(r.get("charged_amount"))));
        }
        return new TabularReport(TITLES.get("vendas"), "Vendas", file(w, "vendas"), headers, out,
                List.of("Quantidade de vendas: " + rows.size() + "   |   Total das vendas: " + TabularReport.Money.brl(gross),
                        "Vendas canceladas até a data de corte ficam fora."),
                flags(headers, "Valor da venda", "Taxa", "Valor cobrado"), new int[]{7, 16, 12, 10, 10, 8, 5, 9, 7, 9});
    }

    // ------------------------------------------------------------ recebidos e a receber

    /** Número da parcela entre todas as da venda (não só as que entram no filtro). */
    private static final String PARCEL = """
            (SELECT count(*) FROM finance_entries x WHERE x.optic_id=e.optic_id AND x.sale_id=e.sale_id
               AND x.origin='SALE' AND x.direction='INCOME' AND (x.due_on, x.id) <= (e.due_on, e.id)) AS parcel,
            (SELECT count(*) FROM finance_entries x WHERE x.optic_id=e.optic_id AND x.sale_id=e.sale_id
               AND x.origin='SALE' AND x.direction='INCOME') AS parcels""";

    /**
     * Recebidos: cada baixa que vale no corte (inclusive parcial). A receber: o saldo de cada parcela no corte.
     * Somas iguais a "Vendas líquidas" e "Vendas a receber" do resumo.
     */
    private TabularReport receivables(MonthlySalesSummaryService.Window w, boolean settled) {
        List<Map<String, Object>> rows;
        if (settled) {
            Object[] a = w.settlementParams(), b = w.receivableParams();
            Object[] params = java.util.Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, params, a.length, b.length);
            rows = jdbc.queryForList("SELECT st.paid_on AS day, s.sold_on, c.full_name AS customer, s.payment_method,"
                    + " m.name AS machine, e.gross_amount, e.fee_amount, e.net_amount, st.amount AS value, 0 AS received,"
                    + PARCEL + """
                     FROM finance_settlements st
                    JOIN finance_entries e ON e.optic_id=st.optic_id AND e.id=st.entry_id
                    JOIN sales s ON s.optic_id=e.optic_id AND s.id=e.sale_id
                    JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                    LEFT JOIN card_machines m ON m.optic_id=s.optic_id AND m.id=s.card_machine_id
                    WHERE""" + MonthlySalesSummaryService.SETTLEMENT_AS_OF + " AND"
                    + MonthlySalesSummaryService.VALID_RECEIVABLE + " ORDER BY st.paid_on, s.sold_on, st.created_at", params);
        } else {
            rows = jdbc.queryForList("SELECT e.due_on AS day, s.sold_on, c.full_name AS customer, s.payment_method,"
                    + " m.name AS machine, e.gross_amount, e.fee_amount, e.net_amount, e.net_amount-rc.received AS value,"
                    + " rc.received," + PARCEL + """
                     FROM finance_entries e
                    JOIN sales s ON s.optic_id=e.optic_id AND s.id=e.sale_id
                    JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                    LEFT JOIN card_machines m ON m.optic_id=s.optic_id AND m.id=s.card_machine_id
                    """ + MonthlySalesSummaryService.RECEIVED_AS_OF + " WHERE" + MonthlySalesSummaryService.VALID_RECEIVABLE
                    + " AND e.net_amount-rc.received>0 ORDER BY e.due_on, s.sold_on", w.receivedParams());
        }
        List<String> headers = settled
                ? List.of("Recebido em", "Venda de", "Cliente", "Pagamento", "Parcela", "Maquininha", "Valor da parcela", "Líquido")
                : List.of("Previsto para", "Venda de", "Cliente", "Pagamento", "Parcela", "Maquininha", "Bruto", "Taxa",
                "Já recebido", "Líquido");
        List<List<String>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int partial = 0;
        for (Map<String, Object> r : rows) {
            BigDecimal value = (BigDecimal) r.get("value");
            total = total.add(value);
            long parcels = ((Number) r.get("parcels")).longValue();
            String parcel = parcels > 1 ? r.get("parcel") + "/" + parcels : "Única";
            if (settled) {
                if (value.compareTo((BigDecimal) r.get("net_amount")) < 0) partial++;
                out.add(List.of(date(r.get("day")), date(r.get("sold_on")), str(r.get("customer")),
                        payment(r.get("payment_method")), parcel, str(r.get("machine")), money(r.get("net_amount")), money(value)));
            } else {
                if (((BigDecimal) r.get("received")).signum() > 0) partial++;
                out.add(List.of(date(r.get("day")), date(r.get("sold_on")), str(r.get("customer")),
                        payment(r.get("payment_method")), parcel, str(r.get("machine")), money(r.get("gross_amount")),
                        money(r.get("fee_amount")), money(r.get("received")), money(value)));
            }
        }
        String kind = settled ? "recebidos" : "a-receber";
        String first = (settled ? "Total recebido (líquido): " : "Total a receber (líquido): ")
                + TabularReport.Money.brl(total) + "   |   " + (settled ? "Recebimentos: " : "Parcelas em aberto: ") + rows.size()
                + (partial > 0 ? "   |   " + (settled ? "Parciais: " : "Com recebimento parcial: ") + partial : "");
        List<String> summary = settled
                ? List.of(first, "Somente recebimentos das vendas deste mês até a data de corte; estornos entram na data em que foram feitos.")
                : List.of(first, "Cartão de crédito e débito entram aqui até a data prevista de recebimento.");
        return new TabularReport(TITLES.get(kind), settled ? "Recebidos" : "A receber", file(w, kind), headers, out,
                summary, flags(headers, "Valor da parcela", "Bruto", "Taxa", "Já recebido", "Líquido"),
                settled ? new int[]{8, 8, 18, 8, 6, 12, 9, 9} : new int[]{8, 8, 16, 8, 6, 11, 8, 6, 8, 8});
    }

    // ------------------------------------------------------------ taxas

    private TabularReport fees(MonthlySalesSummaryService.Window w) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.sold_on, c.full_name AS customer, s.payment_method, s.installments, m.name AS machine,
                       s.gross_amount, s.fee_amount
                FROM sales s
                JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                LEFT JOIN card_machines m ON m.optic_id=s.optic_id AND m.id=s.card_machine_id
                WHERE s.fee_amount>0 AND """ + MonthlySalesSummaryService.VALID_SALE + " ORDER BY s.sold_on, s.created_at",
                w.saleParams());
        List<String> headers = List.of("Data", "Cliente", "Pagamento", "Parcelas", "Maquininha", "Valor da venda",
                "Taxa", "% da taxa");
        List<List<String>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byMethod = new HashMap<>();
        for (Map<String, Object> r : rows) {
            BigDecimal fee = (BigDecimal) r.get("fee_amount"), gross = (BigDecimal) r.get("gross_amount");
            total = total.add(fee);
            byMethod.merge(payment(r.get("payment_method")), fee, BigDecimal::add);
            out.add(List.of(date(r.get("sold_on")), str(r.get("customer")), payment(r.get("payment_method")),
                    str(r.get("installments")), str(r.get("machine")), money(gross), money(fee),
                    fee.multiply(BigDecimal.valueOf(100)).divide(gross, 2, RoundingMode.HALF_UP).toPlainString()
                            .replace('.', ',') + "%"));
        }
        List<String> summary = new ArrayList<>();
        summary.add("Total de taxas: " + TabularReport.Money.brl(total) + "   |   Vendas com taxa: " + rows.size());
        StringBuilder split = new StringBuilder();
        byMethod.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                split.append(split.isEmpty() ? "" : "   |   ").append(e.getKey()).append(": ")
                        .append(TabularReport.Money.brl(e.getValue())));
        if (!split.isEmpty()) summary.add(split.toString());
        return new TabularReport(TITLES.get("taxas"), "Taxas", file(w, "taxas"), headers, out, summary,
                flags(headers, "Valor da venda", "Taxa"), new int[]{8, 20, 9, 6, 14, 10, 8, 7});
    }

    // ------------------------------------------------------------ dias

    private TabularReport days(MonthlySalesSummaryService.Window w) {
        BusinessCalendarController.MonthPlan plan = w.plan();
        List<String> headers = List.of("Data", "Dia", "Loja", "Motivo", "Período");
        List<List<String>> out = new ArrayList<>();
        for (BusinessCalendarController.CalendarDay d : plan.days()) {
            String reason = d.holidays().isEmpty() ? (d.overridden() ? "Ajuste do Master" : (d.normallyOpen() ? "" : "Dia sem expediente"))
                    : String.join(", ", d.holidays().stream().map(BusinessCalendarController.Holiday::name).toList());
            String period = !d.willOpen() ? "—" : d.date().isAfter(w.asOf()) ? "Faltando" : "Trabalhado";
            out.add(List.of(d.date().format(BR), weekday(d.date()), d.willOpen() ? "Aberta" : "Fechada", reason, period));
        }
        return new TabularReport(TITLES.get("dias"), "Dias", file(w, "dias"), headers, out,
                List.of("Dias de funcionamento no mês: " + plan.totalOpenDays() + "   |   Trabalhados até o corte: "
                                + plan.openDaysThroughToday() + "   |   Faltando: " + plan.remainingOpenDays(),
                        "Dia trabalhado é dia em que a loja abre, mesmo sem vendas. Ajuste em Calendário da loja."),
                flags(headers), new int[]{8, 9, 6, 28, 9});
    }

    // ------------------------------------------------------------ metas

    private TabularReport goals(MonthlySalesSummaryService.Window w) {
        MonthlySalesSummaryService.Summary s = engine.compute(w.optic(), w.year(), w.month(), w.asOf());
        Map<LocalDate, BigDecimal> perDay = new HashMap<>();
        jdbc.query("SELECT s.sold_on, sum(s.gross_amount) FROM sales s WHERE " + MonthlySalesSummaryService.VALID_SALE
                        + " GROUP BY s.sold_on",
                rs -> { perDay.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)); }, w.saleParams());
        boolean goal = s.goalConfigured();
        List<String> headers = List.of("Data", "Dia", "Loja", "Vendido no dia", "Acumulado", "Meta acumulada",
                "Diferença");
        List<List<String>> out = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO, expected = BigDecimal.ZERO;
        for (BusinessCalendarController.CalendarDay d : w.plan().days()) {
            boolean future = d.date().isAfter(w.asOf());
            BigDecimal sold = perDay.getOrDefault(d.date(), BigDecimal.ZERO);
            if (!future) accumulated = accumulated.add(sold);
            if (goal && d.willOpen()) expected = expected.add(s.dailyGoal());
            out.add(List.of(d.date().format(BR), weekday(d.date()), d.willOpen() ? "Aberta" : "Fechada",
                    future ? "" : money(sold), future ? "" : money(accumulated), goal ? money(expected) : "",
                    goal && !future ? money(accumulated.subtract(expected)) : ""));
        }
        List<String> summary = new ArrayList<>();
        if (goal) {
            summary.add("Meta do mês: " + TabularReport.Money.brl(s.monthlyGoal()) + "   |   Meta por dia: "
                    + TabularReport.Money.brl(s.dailyGoal()) + "   |   Faltando: " + TabularReport.Money.brl(s.monthlyGoalRemaining()));
            summary.add("Vendido por dia (média real): " + brlOrDash(s.actualDailySales()) + "   |   Precisa vender por dia: "
                    + brlOrDash(s.requiredDailyGoal()) + " nos " + s.remainingDays() + " dias que faltam");
        } else {
            summary.add("Meta do mês não configurada. Cadastre em Relatórios para ver meta acumulada e diferença.");
            summary.add("Vendido por dia (média real): " + brlOrDash(s.actualDailySales()));
        }
        return new TabularReport(TITLES.get("metas"), "Metas", file(w, "metas"), headers, out, summary,
                flags(headers, "Vendido no dia", "Acumulado", "Meta acumulada", "Diferença"), new int[]{8, 8, 6, 10, 10, 10, 10});
    }

    // ------------------------------------------------------------ utilidades

    private static String brlOrDash(BigDecimal v) {
        return v == null ? "—" : TabularReport.Money.brl(v);
    }

    private static String weekday(LocalDate d) {
        String name = d.getDayOfWeek().getDisplayName(TextStyle.FULL, PT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean[] flags(List<String> headers, String... money) {
        boolean[] f = new boolean[headers.size()];
        for (int i = 0; i < headers.size(); i++) f[i] = List.of(money).contains(headers.get(i));
        return f;
    }

    private static String payment(Object method) {
        return DetailedReportController.PAYMENT.getOrDefault(str(method), str(method));
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String money(Object v) {
        return v == null ? "" : ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String date(Object v) {
        if (v == null) return "";
        LocalDate d = v instanceof java.sql.Date sql ? sql.toLocalDate() : LocalDate.parse(v.toString());
        return d.format(BR);
    }
}
