package br.com.lcmtech.optics.finance;

import br.com.lcmtech.optics.finance.domain.CardFees;
import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Cadastro adaptado do PhysioEvo: débito, crédito à vista por prazo de
 * recebimento e crédito parcelado separado em taxa por venda e parcelamento.
 * Todas as operações usam o optic_id da sessão (nunca enviado pelo cliente).
 */
@RestController
@RequestMapping("/api/v1/machines")
public class CardMachineController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;

    public CardMachineController(JdbcTemplate jdbc, OpticContext context) {
        this.jdbc=jdbc;
        this.context=context;
    }

    public record InstallmentFee(Integer installments, BigDecimal fee,
                                 BigDecimal saleFee, BigDecimal installmentFee) {
        public InstallmentFee normalized() {
            if(installments==null||installments<2||installments>12)
                throw new IllegalArgumentException("As parcelas devem ficar entre 2x e 12x.");
            BigDecimal sale=saleFee==null?fee:saleFee;
            if(sale==null)
                throw new IllegalArgumentException("Informe a taxa por venda em "+installments+"x.");
            BigDecimal part=installmentFee==null?BigDecimal.ZERO:installmentFee;
            validateRate(sale, "Taxa por venda em "+installments+"x");
            validateRate(part, "Taxa de parcelamento em "+installments+"x");
            BigDecimal combined=sale.add(part);
            validateRate(combined, "Taxa total em "+installments+"x");
            if(fee!=null&&fee.compareTo(combined)!=0)
                throw new IllegalArgumentException("A taxa total de "+installments+"x não confere com as parcelas.");
            return new InstallmentFee(installments,combined,sale,part);
        }
    }

    public record CreditTerm(Integer days,BigDecimal fee) {}

    public record MachineRequest(
        String name,BigDecimal debitFee,BigDecimal creditFee,BigDecimal fixedFee,
        Integer debitDays,Integer creditDays,Boolean anticipated,
        List<InstallmentFee> installmentFees,List<CreditTerm> creditTerms) {}

    public record MachineView(UUID id,String name,BigDecimal debitFee,BigDecimal creditFee,
            BigDecimal fixedFee,int debitDays,int creditDays,boolean anticipated,boolean active,
            List<InstallmentFee> installmentFees,List<CreditTerm> creditTerms) {}

    @GetMapping
    public List<MachineView> list(Authentication auth,HttpSession session) {
        requireVisibleToSales(auth,session);
        UUID optic=context.optic(auth,session);
        return jdbc.query("SELECT id FROM card_machines WHERE optic_id=? ORDER BY active DESC,name",
                (rs,n)->(UUID)rs.getObject(1),optic)
                .stream().map(id->find(optic,id)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public MachineView create(@RequestBody MachineRequest req,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        Configuration cfg=validate(req);
        UUID optic=context.optic(auth,session),id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO card_machines(optic_id,id,name,debit_fee,credit_fee,fixed_fee,
                debit_days,credit_days,anticipated)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,optic,id,cfg.name(),req.debitFee(),cfg.creditFee(),cfg.fixedFee(),
                cfg.debitDays(),cfg.creditDays(),cfg.anticipated());
        writeRates(optic,id,cfg);
        return find(optic,id);
    }

    @PutMapping("/{id}")
    @Transactional
    public MachineView update(@PathVariable UUID id,@RequestBody MachineRequest req,
            Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        UUID optic=context.optic(auth,session);
        if(!find(optic,id).active())
            throw new IllegalArgumentException("Esta maquininha está desativada.");
        Configuration cfg=validate(req);
        jdbc.update("""
            UPDATE card_machines SET name=?,debit_fee=?,credit_fee=?,fixed_fee=?,
                debit_days=?,credit_days=?,anticipated=?
            WHERE optic_id=? AND id=? AND active
            """,cfg.name(),req.debitFee(),cfg.creditFee(),cfg.fixedFee(),
                cfg.debitDays(),cfg.creditDays(),cfg.anticipated(),optic,id);
        jdbc.update("DELETE FROM card_machine_rates WHERE optic_id=? AND machine_id=?",optic,id);
        jdbc.update("DELETE FROM card_machine_credit_terms WHERE optic_id=? AND machine_id=?",optic,id);
        writeRates(optic,id,cfg);
        return find(optic,id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivate(@PathVariable UUID id,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        UUID optic=context.optic(auth,session);
        int count=jdbc.update("UPDATE card_machines SET active=FALSE WHERE optic_id=? AND id=? AND active",
                optic,id);
        if(count==0)throw new IllegalArgumentException("Maquininha inexistente ou já desativada.");
    }

    private record Configuration(String name,BigDecimal creditFee,BigDecimal fixedFee,
            int debitDays,int creditDays,boolean anticipated,
            List<InstallmentFee> installments,List<CreditTerm> terms) {}

    private static Configuration validate(MachineRequest req) {
        if(req==null||req.name()==null||req.name().isBlank()||
           req.name().trim().length()>60||req.debitFee()==null||req.creditFee()==null)
            throw new IllegalArgumentException("Informe nome (até 60 caracteres) e as taxas da maquininha.");
        validateRate(req.debitFee(),"Débito");
        validateRate(req.creditFee(),"Crédito à vista");
        int debitDays=req.debitDays()==null?1:req.debitDays();
        int creditDays=req.creditDays()==null?30:req.creditDays();
        validateDays(debitDays);
        validateDays(creditDays);
        BigDecimal fixed=req.fixedFee()==null?BigDecimal.ZERO:req.fixedFee();
        if(fixed.signum()<0||fixed.compareTo(new BigDecimal("100"))>0||fixed.scale()>2)
            throw new IllegalArgumentException("A tarifa fixa deve ficar entre R$ 0 e R$ 100.");
        List<CreditTerm> terms=req.creditTerms()==null?
                List.of(new CreditTerm(creditDays,req.creditFee())):req.creditTerms();
        if(terms.isEmpty()||terms.size()>61)
            throw new IllegalArgumentException("Cadastre pelo menos um prazo de crédito à vista.");
        Set<Integer> uniqueDays=new HashSet<>();
        boolean defaultTerm=false;
        for(CreditTerm term:terms) {
            if(term==null||term.days()==null)
                throw new IllegalArgumentException("Informe um prazo válido.");
            validateDays(term.days());
            validateRate(term.fee(),"Crédito à vista em "+term.days()+" dias");
            if(!uniqueDays.add(term.days()))
                throw new IllegalArgumentException("Prazo de recebimento repetido.");
            if(term.days()==creditDays&&term.fee().compareTo(req.creditFee())==0)
                defaultTerm=true;
        }
        if(!defaultTerm)
            throw new IllegalArgumentException("O prazo padrão precisa usar a taxa de crédito à vista selecionada.");

        List<InstallmentFee> rates=req.installmentFees()==null?List.of():
                req.installmentFees().stream().map(item->{
                    if(item==null)throw new IllegalArgumentException("Parcela inválida.");
                    return item.normalized();
                }).toList();
        if(rates.size()>11)throw new IllegalArgumentException("São aceitas até 12 parcelas.");
        Set<Integer> uniqueInstallments=new HashSet<>();
        int max=1;
        for(InstallmentFee rate:rates) {
            if(!uniqueInstallments.add(rate.installments()))
                throw new IllegalArgumentException("Parcela repetida.");
            max=Math.max(max,rate.installments());
        }
        if(!rates.isEmpty()&&uniqueInstallments.size()!=max-1)
            throw new IllegalArgumentException("Cadastre todas as taxas de 2x até "+max+"x.");
        return new Configuration(req.name().trim(),req.creditFee(),fixed,
                debitDays,creditDays,Boolean.TRUE.equals(req.anticipated())&&!rates.isEmpty(),
                rates,terms);
    }

    private void writeRates(UUID optic,UUID id,Configuration cfg) {
        for(InstallmentFee fee:cfg.installments()) {
            jdbc.update("""
                INSERT INTO card_machine_rates(optic_id,machine_id,installments,fee,
                    sale_fee,installment_fee)
                VALUES(?,?,?,?,?,?)
                """,optic,id,fee.installments(),fee.fee(),fee.saleFee(),fee.installmentFee());
        }
        for(CreditTerm term:cfg.terms()) {
            jdbc.update("""
                INSERT INTO card_machine_credit_terms(optic_id,machine_id,days,fee)
                VALUES(?,?,?,?)
                """,optic,id,term.days(),term.fee());
        }
    }

    private MachineView find(UUID optic,UUID id) {
        List<InstallmentFee> installmentFees=jdbc.query("""
            SELECT installments,fee,sale_fee,installment_fee FROM card_machine_rates
            WHERE optic_id=? AND machine_id=? ORDER BY installments
            """,(rs,n)->new InstallmentFee(rs.getInt(1),rs.getBigDecimal(2),
                    rs.getBigDecimal(3),rs.getBigDecimal(4)),optic,id);
        List<CreditTerm> terms=jdbc.query("""
            SELECT days,fee FROM card_machine_credit_terms
            WHERE optic_id=? AND machine_id=? ORDER BY days
            """,(rs,n)->new CreditTerm(rs.getInt(1),rs.getBigDecimal(2)),optic,id);
        return jdbc.query("""
            SELECT id,name,debit_fee,credit_fee,fixed_fee,debit_days,credit_days,anticipated,active
            FROM card_machines WHERE optic_id=? AND id=?
            """,(rs,n)->new MachineView((UUID)rs.getObject("id"),rs.getString("name"),
                    rs.getBigDecimal("debit_fee"),rs.getBigDecimal("credit_fee"),
                    rs.getBigDecimal("fixed_fee"),rs.getInt("debit_days"),
                    rs.getInt("credit_days"),rs.getBoolean("anticipated"),
                    rs.getBoolean("active"),installmentFees,terms),optic,id)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("Maquininha não encontrada."));
    }

    /**
     * Fonte única do cálculo de cartão. SalesController e preview consultam
     * exatamente os mesmos prazos e taxas persistidas.
     */
    public CardFees.Machine fees(UUID optic,UUID id) {
        MachineView machine=find(optic,id);
        if(!machine.active())throw new IllegalArgumentException("Maquininha desativada.");
        Map<Integer,BigDecimal> installmentFees=new HashMap<>();
        for(var fee:machine.installmentFees())installmentFees.put(fee.installments(),fee.fee());
        Map<Integer,BigDecimal> terms=new HashMap<>();
        for(var term:machine.creditTerms())terms.put(term.days(),term.fee());
        if(terms.isEmpty())terms.put(machine.creditDays(),machine.creditFee());
        return new CardFees.Machine(machine.debitFee(),machine.creditFee(),
                installmentFees,machine.fixedFee(),machine.debitDays(),machine.creditDays(),
                machine.anticipated(),terms);
    }

    public record Preview(BigDecimal amount,String paymentMethod,int installments,
            UUID cardMachineId,boolean passFee,Integer settlementDays) {}

    @PostMapping("/preview")
    public CardFees.Calculation preview(@RequestBody Preview req,
            Authentication auth,HttpSession session) {
        requireVisibleToSales(auth,session);
        if(req==null||req.amount()==null||req.amount().signum()<=0)
            throw new IllegalArgumentException("Informe um valor positivo para a simulação.");
        CardFees.Method method;
        try {method=CardFees.Method.valueOf(req.paymentMethod());}
        catch(Exception e) {throw new IllegalArgumentException("Forma de pagamento inválida.");}
        CardFees.Machine machine=null;
        if(method==CardFees.Method.DEBITO||method==CardFees.Method.CREDITO) {
            if(req.cardMachineId()==null)throw new IllegalArgumentException("Selecione a maquininha.");
            machine=fees(context.optic(auth,session),req.cardMachineId());
        }else if(req.cardMachineId()!=null) {
            throw new IllegalArgumentException("Não selecione maquininha para Pix ou dinheiro.");
        }
        return CardFees.calculate(req.amount(),method,req.installments()==0?1:req.installments(),
                machine,req.passFee(),LocalDate.now(),req.settlementDays());
    }

    /** Vendedores precisam escolher uma maquininha e simular taxas sem acessar o caixa inteiro. */
    private void requireVisibleToSales(Authentication auth,HttpSession session) {
        UUID account=context.accountId(auth),optic=context.optic(auth,session);
        if(context.can(account,optic,"FINANCE_READ"))context.require(auth,session,"FINANCE_READ");
        else context.require(auth,session,"SALES_CREATE");
    }

    private static void validateRate(BigDecimal rate,String name) {
        if(rate==null||rate.signum()<0||rate.compareTo(new BigDecimal("30"))>0||rate.scale()>2)
            throw new IllegalArgumentException(name+": informe uma taxa entre 0 e 30% com até 2 casas.");
    }

    private static void validateDays(int value) {
        if(value<0||value>60)
            throw new IllegalArgumentException("O prazo de recebimento deve ficar entre 0 e 60 dias.");
    }
}
