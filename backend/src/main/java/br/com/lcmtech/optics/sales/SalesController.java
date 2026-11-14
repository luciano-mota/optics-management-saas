package br.com.lcmtech.optics.sales;

import br.com.lcmtech.optics.shop.OpticClock;
import br.com.lcmtech.optics.customers.CustomerController;
import br.com.lcmtech.optics.finance.CardMachineController;
import br.com.lcmtech.optics.finance.domain.CardFees;
import br.com.lcmtech.optics.finance.domain.CardFees.Calculation;
import br.com.lcmtech.optics.finance.domain.CardFees.Machine;
import br.com.lcmtech.optics.sales.domain.SaleStatus;
import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Venda é operação atômica: cliente existente, itens da mesma ótica, financeiro
 * e trilha de alteração. Recebíveis no cartão não viram dinheiro no caixa até liquidação.
 */
@RestController
@RequestMapping("/api/v1/sales")
public class SalesController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final CustomerController customers;
    private final CardMachineController machineRegistry;
    private final OpticClock clock;
    private final br.com.lcmtech.optics.stock.StockService stock;
    private final br.com.lcmtech.optics.finance.FinanceLedger ledger;
    public SalesController(JdbcTemplate jdbc,OpticContext context,CustomerController customers,
                           CardMachineController machineRegistry,OpticClock clock,
                           br.com.lcmtech.optics.stock.StockService stock,
                           br.com.lcmtech.optics.finance.FinanceLedger ledger) {
        this.jdbc=jdbc;this.context=context;this.customers=customers;
        this.machineRegistry=machineRegistry;this.clock=clock;this.stock=stock;this.ledger=ledger;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(defaultValue="") String status,
                                          @RequestParam(defaultValue="") String lensBrand,
                                          @RequestParam(defaultValue="") String frameBrand,
                                          Authentication auth,HttpSession session) {
        context.require(auth,session,"SALES_READ");
        return jdbc.queryForList("SELECT s.id,s.customer_id,c.full_name AS customer_name,c.phone AS customer_phone,s.sold_on,"
                +"s.expected_delivery,s.status,s.replacement_months,s.gross_amount,s.charged_amount,"
                +"s.fee_amount,s.net_amount,s.payment_method,s.installments,"
                +"l.brand AS lens_brand, f.brand AS frame_brand,a.full_name AS seller_name,"
                +"s.created_at,s.delivered_at,s.warranty_months,"
                +"CASE WHEN s.warranty_months>0 THEN (s.sold_on+make_interval(months=>s.warranty_months))::date END AS warranty_until,"
                +"(SELECT coalesce(sum(st.amount),0) FROM finance_settlements st JOIN finance_entries e "
                +"ON e.optic_id=st.optic_id AND e.id=st.entry_id WHERE e.optic_id=s.optic_id AND e.sale_id=s.id "
                +"AND st.reversed_at IS NULL) AS received_amount "
                +"FROM sales s JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id "
                +"JOIN catalog_items l ON l.optic_id=s.optic_id AND l.id=s.lens_id "
                +"JOIN catalog_items f ON f.optic_id=s.optic_id AND f.id=s.frame_id "
                +"JOIN accounts a ON a.id=s.seller_id "
                +"WHERE s.optic_id=? AND (?='' OR s.status=?) "
                +"AND (?='' OR lower(l.brand) LIKE lower(?)) "
                +"AND (?='' OR lower(f.brand) LIKE lower(?)) "
                +"ORDER BY s.created_at DESC LIMIT 500",
                context.optic(auth,session),status,status,lensBrand,"%"+lensBrand+"%",
                frameBrand,"%"+frameBrand+"%");
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(Authentication auth,HttpSession session) {
        context.require(auth,session,"SALES_READ");
        UUID optic=context.optic(auth,session);
        java.time.LocalDate today=clock.today(optic);
        Map<String,Object> counts=jdbc.queryForMap("SELECT "
            +"count(*) FILTER (WHERE sold_on=?::date AND status<>'CANCELLED') AS today,"
            +"count(*) FILTER (WHERE sold_on>=date_trunc('week',?::date)::date AND status<>'CANCELLED') AS week,"
            +"count(*) FILTER (WHERE sold_on>=date_trunc('month',?::date)::date AND status<>'CANCELLED') AS month,"
            +"count(*) FILTER (WHERE status IN ('AWAITING_PRODUCTION','READY_FOR_DELIVERY')) AS pending_delivery,"
            +"coalesce(sum(charged_amount) FILTER (WHERE sold_on>=date_trunc('month',?::date)::date "
            +"AND status<>'CANCELLED'),0) AS month_gross FROM sales WHERE optic_id=?",
            today,today,today,today,optic);
        Integer pendingVisits=jdbc.queryForObject("SELECT count(*) FROM home_visits WHERE optic_id=? "
                +"AND status IN ('REQUESTED','CLAIMED','CONFIRMED')",Integer.class,optic);
        Map<String,Object> answer=new HashMap<>(counts);
        answer.put("pendingVisits",pendingVisits);
        return answer;
    }

    private static final Map<String,String> LABEL=Map.of("AWAITING_PRODUCTION","Aguardando produção",
            "READY_FOR_DELIVERY","Pronto para entrega","DELIVERED","Entregue","CANCELLED","Cancelada");

    public record NewSale(UUID customerId,UUID lensId,UUID frameId,
           BigDecimal amount,String paymentMethod,UUID cardMachineId,
           int installments,boolean passFee,Integer settlementDays,
           int replacementMonths,LocalDate soldOn,LocalDate expectedDelivery,
           String notes,String idempotencyKey,Integer warrantyMonths){}

    /**
     * Cliente e venda na mesma transação: um erro na venda desfaz também o
     * cadastro iniciado neste formulário. Evita cadastro órfão no caixa.
     */
    public record NewSaleWithCustomer(
            CustomerController.Customer customer, NewSale sale) {}

    public record NewSaleWithPatient(
            UUID patientId, CustomerController.Customer customer, NewSale sale) {}

    /**
     * Uma consulta não cria cliente. Apenas uma venda confirmada cria ou
     * reaproveita customer, vinculando o paciente com rastreabilidade.
     */
    @PostMapping("/with-patient")
    @Transactional
    public Map<String,Object> createWithPatient(
            @RequestBody NewSaleWithPatient request,
            Authentication auth, HttpSession session) {
        context.require(auth, session, "PATIENTS_READ");
        context.require(auth, session, "CUSTOMERS_WRITE");
        context.require(auth, session, "SALES_CREATE");
        if(request==null||request.patientId()==null||
                request.customer()==null||request.sale()==null)
            throw new IllegalArgumentException("Selecione o paciente e complete a venda.");
        UUID optic=context.optic(auth,session);
        CustomerController.Customer data=request.customer();
        NewSale sale=request.sale();
        List<Map<String,Object>> patients=jdbc.queryForList(
                "SELECT cpf FROM optometry_patients WHERE optic_id=? AND id=? FOR UPDATE",
                optic,request.patientId());
        if(patients.isEmpty()) throw new IllegalArgumentException("Paciente não encontrado.");
        String knownCpf=(String)patients.get(0).get("cpf");
        String cpf=data.cpf()==null?"":data.cpf().replaceAll("\\D","");
        if(!cpf.matches("\\d{11}")||
                (knownCpf!=null&&!knownCpf.equals(cpf)))
            throw new IllegalArgumentException("Verifique o CPF: deve corresponder ao paciente.");
        if(sale.idempotencyKey()!=null&&!sale.idempotencyKey().isBlank()) {
            List<UUID> prior=jdbc.query("SELECT id FROM sales WHERE optic_id=? AND idempotency_key=?",
                    (rs,n)->(UUID)rs.getObject(1),optic,sale.idempotencyKey());
            if(!prior.isEmpty())return Map.of("id",prior.get(0),"alreadyCreated",true);
        }
        List<Map<String,Object>> existing=jdbc.queryForList(
                "SELECT id,source_patient_id FROM customers "
                +"WHERE optic_id=? AND (source_patient_id=? OR cpf=?) FOR UPDATE",
                optic,request.patientId(),cpf);
        UUID customer;
        if(existing.isEmpty()) {
            customer=(UUID)customers.create(data,auth,session).get("id");
            jdbc.update("UPDATE customers SET source_patient_id=? WHERE optic_id=? AND id=?",
                    request.patientId(),optic,customer);
        }else {
            if(existing.size()>1)throw new IllegalArgumentException(
                    "Existem clientes divergentes com esse CPF. Solicite revisão do cadastro.");
            UUID previous=(UUID)existing.get(0).get("source_patient_id");
            if(previous!=null&&!previous.equals(request.patientId()))
                throw new IllegalArgumentException("Este CPF já está vinculado a outro paciente.");
            customer=(UUID)existing.get(0).get("id");
            jdbc.update("UPDATE customers SET source_patient_id=COALESCE(source_patient_id,?),"
                    +"updated_at=now() WHERE optic_id=? AND id=?",request.patientId(),optic,customer);
        }
        Map<String,Object> result=create(new NewSale(customer,sale.lensId(),sale.frameId(),
                sale.amount(),sale.paymentMethod(),sale.cardMachineId(),sale.installments(),
                sale.passFee(),sale.settlementDays(),sale.replacementMonths(),
                sale.soldOn(),sale.expectedDelivery(),sale.notes(),sale.idempotencyKey(),sale.warrantyMonths()),
                auth,session);
        jdbc.update("INSERT INTO audit_events(id,optic_id,actor_account_id,"
                +"event_type,entity_type,entity_id) "
                +"VALUES(?,?,?,'PATIENT_CONVERTED_ON_SALE','CUSTOMER',?)",
                UUID.randomUUID(),optic,context.accountId(auth),customer);
        return Map.of("customerId",customer,"sale",result);
    }

    @PostMapping("/with-customer")
    @Transactional
    public Map<String,Object> createWithCustomer(
            @RequestBody NewSaleWithCustomer request,
            Authentication auth, HttpSession session) {
        context.require(auth, session, "CUSTOMERS_WRITE");
        context.require(auth, session, "SALES_CREATE");
        if (request == null || request.customer() == null || request.sale() == null) {
            throw new IllegalArgumentException("Informe o cliente e os dados da venda.");
        }
        NewSale sale = request.sale();
        if (sale.idempotencyKey() != null && !sale.idempotencyKey().isBlank()) {
            UUID optic = context.optic(auth, session);
            List<UUID> prior = jdbc.query(
                    "SELECT id FROM sales WHERE optic_id=? AND idempotency_key=?",
                    (rs, n) -> (UUID) rs.getObject(1), optic, sale.idempotencyKey());
            if (!prior.isEmpty()) return Map.of("id", prior.get(0), "alreadyCreated", true);
        }
        UUID customer = (UUID) customers.create(request.customer(), auth, session).get("id");
        return create(new NewSale(customer, sale.lensId(), sale.frameId(),
                sale.amount(), sale.paymentMethod(), sale.cardMachineId(),
                sale.installments(), sale.passFee(), sale.settlementDays(),
                sale.replacementMonths(), sale.soldOn(), sale.expectedDelivery(),
                sale.notes(), sale.idempotencyKey(), sale.warrantyMonths()), auth, session);
    }

    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody NewSale request,Authentication auth,HttpSession session) {
        context.require(auth,session,"SALES_CREATE");
        UUID optic=context.optic(auth,session),seller=context.accountId(auth);
        if(request==null||request.customerId()==null||request.lensId()==null||request.frameId()==null)
            throw new IllegalArgumentException("Selecione o cliente, a lente e a armação.");
        if(request.amount()==null||request.amount().signum()<=0)
            throw new IllegalArgumentException("O valor da venda deve ser positivo.");
        if(request.replacementMonths()!=6&&request.replacementMonths()!=12)
            throw new IllegalArgumentException("Escolha 6 ou 12 meses para troca dos óculos.");
        if(request.warrantyMonths()!=null&&(request.warrantyMonths()<0||request.warrantyMonths()>60))
            throw new IllegalArgumentException("Garantia deve ser de 0 a 60 meses.");
        if(request.idempotencyKey()!=null&&!request.idempotencyKey().isBlank()) {
            List<UUID> prior=jdbc.query("SELECT id FROM sales WHERE optic_id=? AND idempotency_key=?",
                    (rs,n)->(UUID)rs.getObject(1),optic,request.idempotencyKey());
            if(!prior.isEmpty())return Map.of("id",prior.get(0),"alreadyCreated",true);
        }
        requireRelated(optic,"customers",request.customerId(),null);
        requireRelated(optic,"catalog_items",request.lensId(),"LENS");
        requireRelated(optic,"catalog_items",request.frameId(),"FRAME");
        CardFees.Method method;
        try {method=CardFees.Method.valueOf(request.paymentMethod());}
        catch(Exception e) {throw new IllegalArgumentException("Escolha uma forma de pagamento.");}
        int installments=request.installments()==0?1:request.installments();
        LocalDate soldOn=request.soldOn()==null?clock.today(optic):request.soldOn();
        Machine machine=loadMachine(optic,method,request.cardMachineId());
        Calculation total=CardFees.calculate(request.amount(),method,installments,machine,
                request.passFee(),soldOn,request.settlementDays());
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,sold_on,"
                +"expected_delivery,replacement_months,gross_amount,fee_amount,charged_amount,net_amount,"
                +"payment_method,installments,card_machine_id,notes,idempotency_key,warranty_months) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                optic,id,request.customerId(),seller,request.lensId(),request.frameId(),soldOn,
                request.expectedDelivery(),request.replacementMonths(),request.amount(),
                total.feeAmount(),total.chargedAmount(),total.netAmount(),method.name(),
                installments,request.cardMachineId(),request.notes(),request.idempotencyKey(),request.warrantyMonths());
        // Estoque: baixa automática da lente e da armação que controlam estoque.
        stock.saleOut(optic,id,seller,request.lensId(),request.frameId());
        if(total.receivables().isEmpty()) {
            insertFinance(optic,id,seller,soldOn,soldOn,soldOn,
                    total.chargedAmount(),total.feeAmount(),total.netAmount());
        } else {
            for(var receivable:total.receivables()) {
                BigDecimal gross=receivable.amount().add(receivable.fee());
                insertFinance(optic,id,seller,soldOn,receivable.dueOn(),null,
                        gross,receivable.fee(),receivable.amount());
            }
        }
        jdbc.update("INSERT INTO sale_events(id,optic_id,sale_id,actor_id,next_status) "
                +"VALUES(?,?,?,?,'AWAITING_PRODUCTION')",UUID.randomUUID(),optic,id,seller);
        return Map.of("id",id,"chargedAmount",total.chargedAmount(),
                "feeAmount",total.feeAmount(),"netAmount",total.netAmount());
    }

    private void insertFinance(UUID optic,UUID sale,UUID actor,LocalDate soldOn,
              LocalDate dueOn,LocalDate settledOn,BigDecimal gross,BigDecimal fee,BigDecimal net) {
        jdbc.update("INSERT INTO finance_entries(optic_id,id,direction,category,description,"
            +"gross_amount,fee_amount,net_amount,occurred_on,due_on,settled_on,origin,sale_id,created_by) "
            +"VALUES(?,?,'INCOME','Venda de óculos','Venda de óculos',?,?,?,?,?,?,'SALE',?,?)",
            optic,UUID.randomUUID(),gross,fee,net,soldOn,dueOn,settledOn,sale,actor);
    }

    private Machine loadMachine(UUID optic,CardFees.Method method,UUID machineId) {
        if(method==CardFees.Method.DINHEIRO||method==CardFees.Method.PIX) {
            if(machineId!=null)throw new IllegalArgumentException("Não indique maquininha para Pix ou dinheiro.");
            return null;
        }
        if(machineId==null)throw new IllegalArgumentException("Escolha a maquininha.");
        return machineRegistry.fees(optic,machineId);
    }

    private void requireRelated(UUID optic,String table,UUID id,String kind) {
        String sql="SELECT count(*) FROM "+table+" WHERE optic_id=? AND id=?"
                +(kind!=null?" AND kind=? AND active":"");
        Integer count=kind==null?jdbc.queryForObject(sql,Integer.class,optic,id):
                jdbc.queryForObject(sql,Integer.class,optic,id,kind);
        if(count==null||count!=1)throw new IllegalArgumentException("Cliente ou produto não encontrado nesta ótica.");
    }

    /**
     * refundedOn/reason: no cancelamento de venda que já teve dinheiro recebido, a data em que o valor foi devolvido ao
     * cliente (padrão: hoje) e o motivo (obrigatório). Os recebimentos são estornados nessa data, na mesma transação.
     */
    public record ChangeStatus(String status,LocalDate refundedOn,String reason){}
    @PatchMapping("/{id}/status")
    @Transactional
    public Map<String,Object> status(@PathVariable UUID id,@RequestBody ChangeStatus request,
                                  Authentication auth,HttpSession session) {
        context.require(auth,session,"SALES_DELIVER");
        UUID optic=context.optic(auth,session),actor=context.accountId(auth);
        String existing=jdbc.query("SELECT status FROM sales WHERE optic_id=? AND id=? FOR UPDATE",
                (rs,n)->rs.getString(1),optic,id).stream().findFirst()
                .orElseThrow(()->new java.util.NoSuchElementException("Venda não encontrada."));
        SaleStatus target;
        try {target=SaleStatus.valueOf(request.status());}
        catch(Exception e){throw new IllegalArgumentException("Situação inválida.");}
        if(!SaleStatus.valueOf(existing).mayTransitionTo(target))
            throw new IllegalArgumentException("Não é possível passar de \""+LABEL.get(existing)+"\" para \""
                    +LABEL.get(target.name())+"\".");
        BigDecimal refunded=BigDecimal.ZERO;
        if(target==SaleStatus.CANCELLED) {
            String reason=request.reason()==null?"":request.reason().trim();
            if(reason.length()>150)throw new IllegalArgumentException("Motivo muito longo (até 150 caracteres).");
            BigDecimal received=ledger.receivedForSale(optic,id);
            if(received.signum()>0) {
                // Devolver dinheiro é operação financeira: quem só entrega óculos não cancela venda já paga.
                context.require(auth,session,"FINANCE_WRITE");
                if(reason.isEmpty())
                    throw new IllegalArgumentException("Informe o motivo do cancelamento: o valor recebido será devolvido ao cliente.");
            }
            for(UUID entry:jdbc.query("SELECT id FROM finance_entries WHERE optic_id=? AND sale_id=? AND reversed_at IS NULL",
                    (rs,n)->(UUID)rs.getObject(1),optic,id))
                refunded=refunded.add(ledger.reverseAllActive(optic,actor,entry,request.refundedOn(),
                        "Venda cancelada"+(reason.isEmpty()?"":": "+reason)));
        }
        jdbc.update("UPDATE sales SET status=?,delivered_at=CASE WHEN ?='DELIVERED' THEN now() "
                +"ELSE delivered_at END, delivered_by=CASE WHEN ?='DELIVERED' THEN ? "
                +"ELSE delivered_by END WHERE optic_id=? AND id=?",
                target.name(),target.name(),target.name(),actor,optic,id);
        jdbc.update("INSERT INTO sale_events(id,optic_id,sale_id,actor_id,previous_status,next_status) "
                +"VALUES(?,?,?,?,?,?)",UUID.randomUUID(),optic,id,actor,existing,target.name());
        if(target==SaleStatus.CANCELLED) {
            jdbc.update("UPDATE finance_entries SET reversed_at=now() WHERE optic_id=? "
                    +"AND sale_id=? AND reversed_at IS NULL",optic,id);
            stock.saleReturn(optic,id,actor);
            return Map.of("updated",true,"status",target.name(),"refunded",refunded);
        }
        return Map.of("updated",true,"status",target.name());
    }
}
