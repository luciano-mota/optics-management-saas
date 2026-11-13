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
    private final FinanceLedger ledger;
    public FinanceController(JdbcTemplate jdbc,OpticContext context,OpticClock clock,FinanceLedger ledger) {
        this.jdbc=jdbc; this.context=context; this.clock=clock; this.ledger=ledger;
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
                +"e.origin,e.sale_id,e.reversed_at, a.full_name AS responsible, "
                +FinanceLedger.ACTIVE_PAID+" AS paid_amount, e.net_amount-"+FinanceLedger.ACTIVE_PAID+" AS open_amount "
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
        // Caixa do mês = baixas ativas do mês (parciais inclusive); a receber = saldo aberto dos lançamentos.
        Map<String,Object> cash=jdbc.queryForMap("SELECT "
            +"coalesce(sum(st.amount) FILTER (WHERE e.direction='INCOME'),0) AS received_month,"
            +"coalesce(sum(st.amount) FILTER (WHERE e.direction='EXPENSE'),0) AS spent_month "
            +"FROM finance_settlements st JOIN finance_entries e ON e.optic_id=st.optic_id AND e.id=st.entry_id "
            +"WHERE st.optic_id=? AND st.reversed_at IS NULL AND e.reversed_at IS NULL "
            +"AND st.paid_on>=date_trunc('month',?::date)::date AND st.paid_on<=?::date",optic,today,today);
        Map<String,Object> open=jdbc.queryForMap("SELECT "
            +"coalesce(sum(e.net_amount-"+FinanceLedger.ACTIVE_PAID+") FILTER (WHERE e.direction='INCOME'),0) AS receivable,"
            +"coalesce(sum(e.net_amount-"+FinanceLedger.ACTIVE_PAID+") FILTER (WHERE e.direction='EXPENSE'),0) AS payable,"
            +"coalesce(sum(e.fee_amount) FILTER (WHERE e.direction='INCOME' "
            +"AND e.occurred_on>=date_trunc('month',?::date)::date),0) AS card_fees_month "
            +"FROM finance_entries e WHERE e.optic_id=? AND e.reversed_at IS NULL",today,optic);
        Map<String,Object> result=new java.util.LinkedHashMap<>(cash);
        result.putAll(open);
        return result;
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

    /** Quita o saldo inteiro (atalho do botão "Recebido"). */
    @PatchMapping("/{id}/settle")
    public Map<String,Object> settle(@PathVariable UUID id,@RequestBody(required=false) Map<String,String> body,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        LocalDate date=parseDate(body==null?null:body.get("settledOn"),"Data de recebimento inválida.");
        var r=ledger.settle(context.optic(auth,session),context.accountId(auth),id,null,date,null);
        return Map.of("updated",true,"settlementId",r.settlementId(),"open",r.open());
    }

    public record SettlementInput(BigDecimal amount,LocalDate paidOn,String note){}

    /** Registra um recebimento (ou pagamento) parcial ou total. */
    @PostMapping("/{id}/settlements")
    public Map<String,Object> addSettlement(@PathVariable UUID id,@RequestBody(required=false) SettlementInput body,
                                            Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        var r=ledger.settle(context.optic(auth,session),context.accountId(auth),id,
                body==null?null:body.amount(),body==null?null:body.paidOn(),body==null?null:body.note());
        return Map.of("settlementId",r.settlementId(),"paid",r.paid(),"open",r.open(),"settled",r.settled());
    }

    @GetMapping("/{id}/settlements")
    public List<Map<String,Object>> settlements(@PathVariable UUID id,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_READ");
        return ledger.history(context.optic(auth,session),id);
    }

    public record ReversalInput(LocalDate reversedOn,String reason){}

    /** Estorna uma baixa com data efetiva; a baixa continua no histórico. */
    @PostMapping("/settlements/{settlementId}/reverse")
    public Map<String,Object> reverseSettlement(@PathVariable UUID settlementId,
            @RequestBody(required=false) ReversalInput body,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        var r=ledger.reverse(context.optic(auth,session),context.accountId(auth),settlementId,
                body==null?null:body.reversedOn(),body==null?null:body.reason());
        return Map.of("reversed",true,"paid",r.paid(),"open",r.open());
    }

    private static LocalDate parseDate(String informed,String message){
        if(informed==null||informed.isBlank())return null;
        try {return LocalDate.parse(informed);}
        catch(Exception e){throw new IllegalArgumentException(message);}
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
