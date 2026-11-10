package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.calendar.BusinessCalendarController;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Consolidação mensal por coorte de vendas. Os 14 indicadores alimentam o
 * painel, o PDF, o Excel e o CSV sem consultas duplicadas entre formatos.
 * Dia trabalhado significa dia de ABERTURA da ótica, mesmo sem vendas.
 */
@Service
public class MonthlySalesSummaryService {
    private final JdbcTemplate jdbc;
    private final BusinessCalendarController calendar;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public MonthlySalesSummaryService(JdbcTemplate jdbc,BusinessCalendarController calendar) {
        this.jdbc=jdbc;this.calendar=calendar;
    }

    public record Summary(
            String opticName,int year,int month,LocalDate asOf,
            boolean goalConfigured,boolean calendarReviewed,boolean localHolidaysVerified,
            int totalOpenDays,int workedDays,int remainingDays,
            BigDecimal netReceived,BigDecimal toReceive,BigDecimal totalSales,
            BigDecimal monthlyGoal,BigDecimal dailyGoal,BigDecimal monthlyGoalRemaining,
            BigDecimal actualDailySales,int salesCount,BigDecimal requiredDailyGoal,
            BigDecimal glassesAndLensesGross,BigDecimal bankFees,
            BigDecimal reconciliationDifference,List<String> warnings) {}

    private static BigDecimal amount(Map<String,Object> data,String key) {
        Object value=data.get(key);
        if(value instanceof BigDecimal n)return n.setScale(2,RoundingMode.HALF_UP);
        if(value instanceof Number n)return new BigDecimal(n.toString()).setScale(2,RoundingMode.HALF_UP);
        return ZERO;
    }

    private static BigDecimal divide(BigDecimal number,int divisor) {
        return divisor>0?number.divide(BigDecimal.valueOf(divisor),2,RoundingMode.HALF_UP):null;
    }

    public Summary compute(UUID optic,int year,int month,LocalDate asOf) {
        if(year<2000||year>2199||month<1||month>12)
            throw new IllegalArgumentException("Mês ou ano inválido.");
        YearMonth period=YearMonth.of(year,month);
        if(asOf==null||!period.equals(YearMonth.from(asOf)))
            throw new IllegalArgumentException("A data de corte deve pertencer ao mês consultado.");
        String name=jdbc.queryForObject("SELECT trade_name FROM optics WHERE id=?",
                String.class,optic);
        var plan=calendar.forReporting(optic,year,month,asOf);
        java.time.ZoneId shopZone=java.time.ZoneId.of(plan.timeZone());
        if(asOf.isAfter(LocalDate.now(shopZone)))
            throw new IllegalArgumentException("Não é possível apurar vendas em uma data futura.");
        java.time.OffsetDateTime endExclusive=asOf.plusDays(1)
                .atStartOfDay(shopZone).toOffsetDateTime();
        List<String> warnings=new ArrayList<>(plan.warnings());

        // A data de criação impede que venda cadastrada retroativamente depois do
        // fechamento seja incluída na fotografia histórica. Cancelamentos são
        // avaliados por evento na data, não somente pelo status atual da venda.
        var sales=jdbc.queryForMap("""
            SELECT COUNT(*) AS sale_count,
                   COALESCE(SUM(s.gross_amount),0) AS gross,
                   COALESCE(SUM(s.charged_amount),0) AS charged,
                   COALESCE(SUM(s.fee_amount),0) AS fees
            FROM sales s
            WHERE s.optic_id=? AND s.sold_on BETWEEN ? AND ?
                AND s.created_at < ?
                AND NOT EXISTS (
                    SELECT 1 FROM sale_events ev WHERE ev.optic_id=s.optic_id
                    AND ev.sale_id=s.id AND ev.next_status='CANCELLED'
                    AND ev.occurred_at < ?
                )
            """,optic,java.sql.Date.valueOf(period.atDay(1)),
                java.sql.Date.valueOf(asOf),endExclusive,endExclusive);
        var settlements=jdbc.queryForMap("""
            SELECT
              COALESCE(SUM(e.net_amount) FILTER
                  (WHERE e.settled_on IS NOT NULL AND e.settled_on<=?),0) AS received,
              COALESCE(SUM(e.net_amount) FILTER
                  (WHERE e.settled_on IS NULL OR e.settled_on>?),0) AS pending
            FROM finance_entries e JOIN sales s
                 ON s.optic_id=e.optic_id AND s.id=e.sale_id
            WHERE e.optic_id=? AND e.origin='SALE' AND e.direction='INCOME'
                AND e.created_at < ?
                AND s.sold_on BETWEEN ? AND ? AND s.created_at < ?
                AND (e.reversed_at IS NULL OR e.reversed_at >= ?)
                AND NOT EXISTS (
                    SELECT 1 FROM sale_events ev WHERE ev.optic_id=s.optic_id
                    AND ev.sale_id=s.id AND ev.next_status='CANCELLED'
                    AND ev.occurred_at < ?
                )
            """,java.sql.Date.valueOf(asOf),java.sql.Date.valueOf(asOf),
                optic,endExclusive,
                java.sql.Date.valueOf(period.atDay(1)),java.sql.Date.valueOf(asOf),
                endExclusive,endExclusive,endExclusive);

        BigDecimal gross=amount(sales,"gross");
        BigDecimal charged=amount(sales,"charged");
        BigDecimal fees=amount(sales,"fees");
        BigDecimal received=amount(settlements,"received");
        BigDecimal pending=amount(settlements,"pending");
        BigDecimal difference=charged.subtract(received).subtract(pending).subtract(fees);
        BigDecimal commercialDifference=gross.subtract(received).subtract(pending).subtract(fees);
        int count=((Number)sales.get("sale_count")).intValue();

        List<BigDecimal> goalRows=jdbc.query("""
            SELECT goal_amount FROM optic_monthly_goals
            WHERE optic_id=? AND calendar_year=? AND calendar_month=?
            """,(rs,n)->rs.getBigDecimal(1),optic,year,month);
        boolean configured=!goalRows.isEmpty();
        BigDecimal goal=configured?goalRows.get(0).setScale(2,RoundingMode.HALF_UP):null;
        BigDecimal remaining=configured?goal.subtract(gross).max(ZERO):null;
        BigDecimal dailyGoal=configured?divide(goal,plan.totalOpenDays()):null;
        BigDecimal dailyActual=divide(gross,plan.openDaysThroughToday());
        BigDecimal required=configured?divide(remaining,plan.remainingOpenDays()):null;

        if(!plan.reviewed())warnings.add("O Master ainda não confirmou os dias de funcionamento deste mês.");
        if(!configured)warnings.add("Configure a meta mensal antes de calcular as metas diárias.");
        if(charged.compareTo(gross)!=0)
            warnings.add("Há taxa repassada ao comprador: valor comercial e valor cobrado diferem.");
        if(difference.signum()!=0)
            warnings.add("Existe diferença entre cobrança, recebíveis líquidos e taxas. Verifique os lançamentos.");
        if(commercialDifference.signum()!=0)
            warnings.add("A conciliação com a venda bruta não fecha. Pode haver taxa repassada ou divergência.");
        if(asOf.isBefore(LocalDate.now(shopZone)))
            warnings.add("Fechamento histórico: baixas informadas retroativamente podem alterar o resultado. Confira a memória de lançamentos.");
        if(configured && plan.remainingOpenDays()==0 && remaining.signum()>0)
            warnings.add("Não restam dias de funcionamento no período e a meta não foi atingida.");

        return new Summary(name,year,month,asOf,configured,plan.reviewed(),
                plan.localHolidaysVerified(),plan.totalOpenDays(),
                plan.openDaysThroughToday(),plan.remainingOpenDays(),
                received,pending,gross,goal,dailyGoal,remaining,dailyActual,count,
                required,gross,fees,difference,List.copyOf(warnings));
    }

    public record Metric(String key,String name,BigDecimal value,boolean money) {}

    /** Ordem idêntica ao relatório da ótica piloto, com 14 linhas. */
    public List<Metric> metrics(Summary s) {
        return List.of(
            new Metric("netReceived","Vendas líquidas",s.netReceived(),true),
            new Metric("toReceive","Vendas a receber",s.toReceive(),true),
            new Metric("totalSales","Total das vendas",s.totalSales(),true),
            new Metric("monthlyGoal","Meta/mês",s.monthlyGoal(),true),
            new Metric("dailyGoal","Meta/dia",s.dailyGoal(),true),
            new Metric("monthlyGoalRemaining","Meta faltando/mês",s.monthlyGoalRemaining(),true),
            new Metric("actualDailySales","Vendas/real por dia",s.actualDailySales(),true),
            new Metric("totalOpenDays","Dias TOTAL - trabalhado/mês",BigDecimal.valueOf(s.totalOpenDays()),false),
            new Metric("workedDays","Dias trabalhados",BigDecimal.valueOf(s.workedDays()),false),
            new Metric("remainingDays","Dias faltando",BigDecimal.valueOf(s.remainingDays()),false),
            new Metric("salesCount","Quantidade vendas/mensal",BigDecimal.valueOf(s.salesCount()),false),
            new Metric("requiredDailyGoal","Meta faltando/dia",s.requiredDailyGoal(),true),
            new Metric("glassesAndLensesGross","Venda bruta - óculos/lentes",s.glassesAndLensesGross(),true),
            new Metric("bankFees","Taxas bancárias",s.bankFees(),true)
        );
    }
}
