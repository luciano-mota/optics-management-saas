package br.com.lcmtech.optics.finance;

import br.com.lcmtech.optics.shop.OpticClock;
import br.com.lcmtech.optics.finance.domain.FinancialAmount;
import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Caixa distingue recebíveis ainda pendentes de entradas efetivamente liquidadas.
 * Lançamentos de vendas são gerados pela SalesController e não editáveis manualmente.
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticClock clock;
    public FinanceController(JdbcTemplate jdbc,OpticContext context,OpticClock clock) {
        this.jdbc=jdbc; this.context=context; this.clock=clock;
    }

    @GetMapping
    public List<Map<String,Object>> entries(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="") String direction,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_READ");
        UUID optic=context.optic(auth,session);
        return jdbc.queryForList("SELECT e.id,e.direction,e.category,e.description,"
                +"e.gross_amount,e.fee_amount,e.net_amount,e.occurred_on,e.due_on,e.settled_on,"
                +"e.origin,e.sale_id,e.reversed_at, a.full_name AS responsible "
                +"FROM finance_entries e JOIN accounts a ON a.id=e.created_by "
                +"WHERE e.optic_id=? AND (?::date IS NULL OR e.occurred_on>=?::date)"
                +" AND (?::date IS NULL OR e.occurred_on<=?::date)"
                +" AND (?='' OR e.direction=?) ORDER BY e.occurred_on DESC,e.created_at DESC LIMIT 1000",
                optic,from,from,to,to,direction,direction);
    }

    @GetMapping("/summary")
    public Map<String,Object> summary(Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_READ");
        UUID optic=context.optic(auth,session);
        LocalDate today=clock.today(optic);
        return jdbc.queryForMap("SELECT "
            +"coalesce(sum(net_amount) FILTER (WHERE direction='INCOME' AND settled_on IS NOT NULL AND "
            +"reversed_at IS NULL AND settled_on>=date_trunc('month',?::date)::date),0) AS received_month,"
            +"coalesce(sum(net_amount) FILTER (WHERE direction='EXPENSE' AND settled_on IS NOT NULL AND "
            +"reversed_at IS NULL AND settled_on>=date_trunc('month',?::date)::date),0) AS spent_month,"
            +"coalesce(sum(net_amount) FILTER (WHERE direction='INCOME' AND settled_on IS NULL "
            +"AND reversed_at IS NULL),0) AS receivable,"
            +"coalesce(sum(fee_amount) FILTER (WHERE direction='INCOME' AND reversed_at IS NULL "
            +"AND occurred_on>=date_trunc('month',?::date)::date),0) AS card_fees_month "
            +"FROM finance_entries WHERE optic_id=?",today,today,today,optic);
    }

    public record ManualEntry(String direction,String category,String description,
                              BigDecimal amount,LocalDate occurredOn,LocalDate dueOn,boolean settled){}
    @PostMapping
    public Map<String,Object> create(@RequestBody ManualEntry req,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        if(req==null||!List.of("INCOME","EXPENSE").contains(req.direction())||
                req.category()==null||req.category().isBlank()||
                req.description()==null||req.description().isBlank())
            throw new IllegalArgumentException("Informe tipo, categoria e descrição.");
        BigDecimal amount=new FinancialAmount(req.amount()).value();
        UUID id=UUID.randomUUID();
        LocalDate day=req.occurredOn()==null?clock.today(context.optic(auth,session)):req.occurredOn();
        jdbc.update("INSERT INTO finance_entries(optic_id,id,direction,category,description,"
                +"gross_amount,net_amount,occurred_on,due_on,settled_on,created_by) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                context.optic(auth,session),id,req.direction(),req.category().trim(),
                req.description().trim(),amount,amount,day,req.dueOn(),
                req.settled()?day:null,context.accountId(auth));
        return Map.of("id",id);
    }

    @PatchMapping("/{id}/settle")
    @Transactional
    public Map<String,Boolean> settle(@PathVariable UUID id,@RequestBody Map<String,String> body,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        LocalDate date;
        String informed=body==null?null:body.get("settledOn");
        if(informed==null||informed.isBlank()) date=clock.today(context.optic(auth,session)); // hoje na ótica
        else try {date=LocalDate.parse(informed);}
        catch(Exception e){throw new IllegalArgumentException("Data de recebimento inválida.");}
        int count=jdbc.update("UPDATE finance_entries SET settled_on=? WHERE optic_id=? AND id=? "
                +"AND settled_on IS NULL AND reversed_at IS NULL",
                date,context.optic(auth,session),id);
        if(count!=1)throw new IllegalArgumentException("Lançamento inexistente ou já liquidado.");
        return Map.of("updated",true);
    }

    @PatchMapping("/{id}/reverse")
    @Transactional
    public Map<String,Boolean> reverse(@PathVariable UUID id,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        int n=jdbc.update("UPDATE finance_entries SET reversed_at=now() WHERE optic_id=? AND id=? "
                +"AND origin='MANUAL' AND reversed_at IS NULL",context.optic(auth,session),id);
        if(n!=1)throw new IllegalArgumentException("Só lançamentos manuais ativos podem ser estornados aqui.");
        return Map.of("reversed",true);
    }
}
