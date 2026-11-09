package br.com.lcmtech.optics.finance;

import br.com.lcmtech.optics.finance.domain.CardFees;
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

@RestController
@RequestMapping("/api/v1/machines")
public class CardMachineController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public CardMachineController(JdbcTemplate jdbc,OpticContext context) {
        this.jdbc=jdbc;this.context=context;
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_READ");
        UUID optic=context.optic(auth,session);
        List<Map<String,Object>> machines=jdbc.queryForList(
                "SELECT id,name,debit_fee,credit_fee,fixed_fee,debit_days,credit_days,anticipated,active "
                +"FROM card_machines WHERE optic_id=? ORDER BY name",optic);
        for(Map<String,Object> machine:machines) {
            machine.put("installmentFees",jdbc.queryForList("SELECT installments,fee "
                    +"FROM card_machine_rates WHERE optic_id=? AND machine_id=? ORDER BY installments",
                    optic,machine.get("id")));
        }
        return machines;
    }

    public record NewMachine(String name,BigDecimal debitFee,BigDecimal creditFee,BigDecimal fixedFee,
           int debitDays,int creditDays,boolean anticipated,Map<Integer,BigDecimal> installmentFees){}
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody NewMachine req,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_WRITE");
        if(req==null||req.name()==null||req.name().isBlank()||
                req.debitFee()==null||req.creditFee()==null)
            throw new IllegalArgumentException("Informe nome e taxas da maquininha.");
        validateRate(req.debitFee());
        validateRate(req.creditFee());
        if(req.debitDays()<0||req.creditDays()<0)
            throw new IllegalArgumentException("Prazo de recebimento inválido.");
        BigDecimal fixed=req.fixedFee()==null?BigDecimal.ZERO:req.fixedFee();
        if(fixed.signum()<0)throw new IllegalArgumentException("Taxa fixa negativa.");
        UUID optic=context.optic(auth,session),id=UUID.randomUUID();
        jdbc.update("INSERT INTO card_machines(optic_id,id,name,debit_fee,credit_fee,fixed_fee,debit_days,"
                +"credit_days,anticipated) VALUES(?,?,?,?,?,?,?,?,?)",optic,id,req.name().trim(),
                req.debitFee(),req.creditFee(),fixed,req.debitDays(),req.creditDays(),req.anticipated());
        if(req.installmentFees()!=null)for(var rate:req.installmentFees().entrySet()){
            if(rate.getKey()<2||rate.getKey()>12)throw new IllegalArgumentException("Parcela deve ficar entre 2x e 12x.");
            validateRate(rate.getValue());
            jdbc.update("INSERT INTO card_machine_rates(optic_id,machine_id,installments,fee) VALUES(?,?,?,?)",
                    optic,id,rate.getKey(),rate.getValue());
        }
        return Map.of("id",id);
    }

    public record Preview(BigDecimal amount,String paymentMethod,int installments,UUID cardMachineId,
                          boolean passFee,Integer settlementDays){}
    @PostMapping("/preview")
    public CardFees.Calculation preview(@RequestBody Preview req,Authentication auth,HttpSession session) {
        context.require(auth,session,"FINANCE_READ");
        if(req==null||req.amount()==null||req.amount().signum()<=0)
            throw new IllegalArgumentException("Valor inválido.");
        CardFees.Method method;
        try {method=CardFees.Method.valueOf(req.paymentMethod());}
        catch(Exception e){throw new IllegalArgumentException("Forma de pagamento inválida.");}
        UUID optic=context.optic(auth,session);
        CardFees.Machine machine=null;
        if(method==CardFees.Method.DEBITO||method==CardFees.Method.CREDITO){
            if(req.cardMachineId()==null)throw new IllegalArgumentException("Selecione a maquininha.");
            Map<String,Object> m=jdbc.queryForList("SELECT * FROM card_machines WHERE optic_id=? AND id=? AND active",
                    optic,req.cardMachineId()).stream().findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Maquininha não encontrada."));
            Map<Integer,BigDecimal> rates=new java.util.HashMap<>();
            jdbc.query("SELECT installments,fee FROM card_machine_rates WHERE optic_id=? AND machine_id=?",
                    rs->{rates.put(rs.getInt(1),rs.getBigDecimal(2));},optic,req.cardMachineId());
            int creditDays=(Integer)m.get("credit_days");
            BigDecimal creditFee=(BigDecimal)m.get("credit_fee");
            machine=new CardFees.Machine((BigDecimal)m.get("debit_fee"),creditFee,rates,
                    (BigDecimal)m.get("fixed_fee"),(Integer)m.get("debit_days"),creditDays,
                    (Boolean)m.get("anticipated"),Map.of(creditDays,creditFee));
        }
        return CardFees.calculate(req.amount(),method,req.installments()==0?1:req.installments(),
                machine,req.passFee(),LocalDate.now(),req.settlementDays());
    }

    private static void validateRate(BigDecimal rate) {
        if(rate==null||rate.signum()<0||rate.compareTo(BigDecimal.valueOf(100))>=0)
            throw new IllegalArgumentException("A taxa deve ficar entre 0 e 99,99%.");
    }
}
