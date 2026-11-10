package br.com.lcmtech.optics.visits;

import br.com.lcmtech.optics.shop.OpticClock;
import br.com.lcmtech.optics.sales.SalesController;
import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.visits.domain.VisitStatus;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Leads não convertidos permanecem separados da base principal de clientes.
 * Atribuição usa UPDATE condicional para impedir dupla aceitação da visita.
 */
@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final SalesController sales;
    private final OpticClock clock;
    public VisitController(JdbcTemplate jdbc,OpticContext context,SalesController sales,OpticClock clock){
        this.jdbc=jdbc;this.context=context;this.sales=sales;this.clock=clock;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(defaultValue="") String status,
                                          Authentication auth,HttpSession session){
        context.require(auth,session,"VISITS_READ");
        return jdbc.queryForList("SELECT v.id,v.lead_name,v.lead_phone,v.lead_email,v.lead_address,"
                +"v.appointment_at,v.status,v.assigned_to,v.customer_id,v.sale_id,v.notes,"
                +"v.created_at,v.updated_at,a.full_name AS assigned_name "
                +"FROM home_visits v LEFT JOIN accounts a ON a.id=v.assigned_to "
                +"WHERE v.optic_id=? AND (?='' OR v.status=?) ORDER BY v.created_at DESC LIMIT 400",
                context.optic(auth,session),status,status);
    }

    @GetMapping("/{id}/events")
    public List<Map<String,Object>> history(@PathVariable UUID id,Authentication auth,HttpSession session) {
        context.require(auth,session,"VISITS_READ");
        UUID optic=context.optic(auth,session);
        return jdbc.queryForList("SELECT e.event,e.occurred_at,a.full_name AS responsible "
                +"FROM visit_events e LEFT JOIN accounts a ON a.id=e.actor_id "
                +"WHERE e.optic_id=? AND e.visit_id=? ORDER BY e.occurred_at",optic,id);
    }

    @PostMapping("/{id}/claim")
    @Transactional
    public Map<String,Boolean> claim(@PathVariable UUID id,Authentication auth,HttpSession session){
        context.require(auth,session,"VISITS_MANAGE");
        UUID optic=context.optic(auth,session),actor=context.accountId(auth);
        int rows=jdbc.update("UPDATE home_visits SET status='CLAIMED',assigned_to=?,updated_at=now() "
                +"WHERE optic_id=? AND id=? AND status='REQUESTED' AND assigned_to IS NULL",actor,optic,id);
        if(rows!=1)throw new IllegalArgumentException("Essa visita já foi assumida ou não está disponível.");
        event(optic,id,actor,"CLAIMED");
        return Map.of("claimed",true);
    }

    public record UpdateVisit(String status){}
    @PatchMapping("/{id}/status")
    @Transactional
    public Map<String,Object> status(@PathVariable UUID id,@RequestBody UpdateVisit req,
                                      Authentication auth,HttpSession session){
        context.require(auth,session,"VISITS_MANAGE");
        UUID optic=context.optic(auth,session),actor=context.accountId(auth);
        Map<String,Object> visit=readForUpdate(optic,id);
        VisitStatus target;
        try{target=VisitStatus.valueOf(req.status());}
        catch(Exception e){throw new IllegalArgumentException("Situação da visita inválida.");}
        VisitStatus.valueOf((String)visit.get("status")).transitionTo(target);
        if(target==VisitStatus.CONVERTED)
            throw new IllegalArgumentException("Para concluir a venda, use o formulário de conversão.");
        UUID assignee=(UUID)visit.get("assigned_to");
        if(assignee!=null&&!assignee.equals(actor)&&!"MASTER".equals(context.role(actor,optic)))
            throw new org.springframework.security.access.AccessDeniedException("Esta visita pertence a outro funcionário.");
        jdbc.update("UPDATE home_visits SET status=?,updated_at=now() WHERE optic_id=? AND id=?",
                target.name(),optic,id);
        event(optic,id,actor,target.name());
        return Map.of("updated",true,"status",target.name());
    }

    public record ConvertVisit(String fullName,String cpf,LocalDate birthDate,
                               UUID lensId,UUID frameId,java.math.BigDecimal amount,
                               String paymentMethod,UUID cardMachineId,int installments,
                               boolean passFee,Integer settlementDays,int replacementMonths,
                               LocalDate expectedDelivery,String notes){}
    @PostMapping("/{id}/convert")
    @Transactional
    public Map<String,Object> convert(@PathVariable UUID id,@RequestBody ConvertVisit req,
                                       Authentication auth,HttpSession session){
        context.require(auth,session,"VISITS_MANAGE");
        context.require(auth,session,"SALES_CREATE");
        UUID optic=context.optic(auth,session),actor=context.accountId(auth);
        Map<String,Object> visit=readForUpdate(optic,id);
        if(!"VISITED".equals(visit.get("status")))
            throw new IllegalArgumentException("Marque a visita como realizada antes de converter em venda.");
        UUID assignee=(UUID)visit.get("assigned_to");
        if(!actor.equals(assignee)&&!"MASTER".equals(context.role(actor,optic)))
            throw new org.springframework.security.access.AccessDeniedException("Esta visita pertence a outro funcionário.");
        if(req.birthDate()==null||req.cpf()==null||req.cpf().isBlank())
            throw new IllegalArgumentException("Informe CPF e data de nascimento para concluir a venda.");
        String cpf=req.cpf().replaceAll("\\D","");
        if(cpf!=null&&!cpf.isEmpty()&&!cpf.matches("\\d{11}"))
            throw new IllegalArgumentException("CPF inválido.");
        String name=req.fullName()==null||req.fullName().isBlank()?
                (String)visit.get("lead_name"):req.fullName().trim();
        List<UUID> known=jdbc.query("SELECT id FROM customers WHERE optic_id=? AND cpf=?",
                (rs,n)->(UUID)rs.getObject(1),optic,cpf);
        UUID customer;
        if(known.isEmpty()){
            customer=UUID.randomUUID();
            jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,birth_date,phone,address) VALUES(?,?,?,?,?,?,?)",
                    optic,customer,name,cpf,req.birthDate(),visit.get("lead_phone"),visit.get("lead_address"));
        } else {
            customer=known.get(0);
            // Se o CPF já pertence a um cliente desta ótica, reaproveitamos o cadastro
            // e evitamos um segundo cliente com o mesmo documento.
        }
        Map<String,Object> result=sales.create(
                new SalesController.NewSale(customer,req.lensId(),req.frameId(),req.amount(),req.paymentMethod(),
                    req.cardMachineId(),req.installments(),req.passFee(),req.settlementDays(),
                    req.replacementMonths(),clock.today(optic),req.expectedDelivery(),req.notes(),"VISIT-"+id),
                    auth,session);
        UUID sale=(UUID)result.get("id");
        jdbc.update("UPDATE home_visits SET status='CONVERTED',customer_id=?,sale_id=?,"
                +"updated_at=now() WHERE optic_id=? AND id=?",customer,sale,optic,id);
        jdbc.update("UPDATE file_attachments SET customer_id=?,sale_id=? "
                +"WHERE optic_id=? AND visit_id=?",customer,sale,optic,id);
        event(optic,id,actor,"CONVERTED");
        return Map.of("converted",true,"customerId",customer,"saleId",sale);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,Boolean> deleteUnconverted(@PathVariable UUID id,Authentication auth,HttpSession session){
        context.requireMaster(auth,session);
        UUID optic=context.optic(auth,session);
        Map<String,Object> visit=readForUpdate(optic,id);
        if(!"NOT_CONVERTED".equals(visit.get("status"))||visit.get("sale_id")!=null)
            throw new IllegalArgumentException("Somente visitas não convertidas podem ser excluídas.");
        jdbc.update("DELETE FROM file_attachments WHERE optic_id=? AND visit_id=?",optic,id);
        jdbc.update("DELETE FROM visit_events WHERE optic_id=? AND visit_id=?",optic,id);
        jdbc.update("DELETE FROM home_visits WHERE optic_id=? AND id=?",optic,id);
        return Map.of("deleted",true);
    }

    private Map<String,Object> readForUpdate(UUID optic,UUID id){
        return jdbc.queryForList("SELECT * FROM home_visits WHERE optic_id=? AND id=? FOR UPDATE",
                optic,id).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("Visita não encontrada."));
    }
    private void event(UUID optic,UUID visit,UUID actor,String status) {
        jdbc.update("INSERT INTO visit_events(id,optic_id,visit_id,actor_id,event) VALUES(?,?,?,?,?)",
                UUID.randomUUID(),optic,visit,actor,status);
    }
}
