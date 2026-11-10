package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** A meta é uma escolha administrativa da ótica, independente do faturamento. */
@RestController
@RequestMapping("/api/v1/sales-goals")
public class SalesGoalController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;

    public SalesGoalController(JdbcTemplate jdbc,OpticContext context) {
        this.jdbc=jdbc;this.context=context;
    }

    @GetMapping
    public Map<String,Object> get(@RequestParam int year,@RequestParam int month,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"REPORTS_READ");
        context.require(auth,session,"FINANCE_READ");
        validatePeriod(year,month);
        UUID optic=context.optic(auth,session);
        return jdbc.query("""
            SELECT goal_amount FROM optic_monthly_goals
            WHERE optic_id=? AND calendar_year=? AND calendar_month=?
            """,(rs,n)->Map.<String,Object>of(
                "configured",true,"goalAmount",rs.getBigDecimal("goal_amount")),
                optic,year,month).stream().findFirst()
                .orElse(Map.of("configured",false));
    }

    public record GoalChange(BigDecimal goalAmount) {}

    @PutMapping("/{year}/{month}")
    @Transactional
    public Map<String,Object> put(@PathVariable int year,@PathVariable int month,
            @RequestBody GoalChange request,Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        validatePeriod(year,month);
        if(request==null||request.goalAmount()==null||
                request.goalAmount().signum()<0||request.goalAmount().scale()>2||
                request.goalAmount().compareTo(new BigDecimal("9999999999999.99"))>0)
            throw new IllegalArgumentException("Informe uma meta válida em reais, com até 2 casas.");
        UUID optic=context.optic(auth,session);
        UUID actor=context.accountId(auth);
        jdbc.update("""
            INSERT INTO optic_monthly_goals
                (optic_id,calendar_year,calendar_month,goal_amount,updated_by)
            VALUES(?,?,?,?,?)
            ON CONFLICT(optic_id,calendar_year,calendar_month)
              DO UPDATE SET goal_amount=excluded.goal_amount,
                            updated_by=excluded.updated_by, updated_at=now()
            """,optic,year,month,request.goalAmount(),actor);
        jdbc.update("""
            INSERT INTO audit_events(id,optic_id,actor_account_id,event_type)
            VALUES(?,?,?,'MONTHLY_GOAL_UPDATED')
            """,UUID.randomUUID(),optic,actor);
        return Map.of("configured",true,"goalAmount",request.goalAmount());
    }

    private static void validatePeriod(int year,int month) {
        if(year<2000||year>2199||month<1||month>12)
            throw new IllegalArgumentException("Mês e ano inválidos.");
        YearMonth.of(year,month);
    }
}
