package br.com.lcmtech.optics.customers;

import br.com.lcmtech.optics.shop.OpticClock;
import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticClock clock;
    public CustomerController(JdbcTemplate jdbc,OpticContext context,OpticClock clock) {
        this.jdbc=jdbc;this.context=context;this.clock=clock;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(defaultValue="") String q,
             @RequestParam(required=false) Integer months,
             Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_READ");
        if(months!=null&&!List.of(3,6,9,12).contains(months))
            throw new IllegalArgumentException("Escolha 3, 6, 9 ou 12 meses.");
        UUID optic=context.optic(auth,session);
        String sql="SELECT c.id,c.full_name,c.cpf,c.birth_date,c.phone,c.address,c.email,c.email_opt_in,c.created_at,"
            +"last_sale.sold_on AS last_sale_on,last_sale.replacement_months,"
            +"(extract(year from age(?::date,last_sale.sold_on))*12"
            +" +extract(month from age(?::date,last_sale.sold_on)))::int AS months_since_sale "
            +"FROM customers c LEFT JOIN LATERAL (SELECT sold_on,replacement_months FROM sales s "
            +"WHERE s.optic_id=c.optic_id AND s.customer_id=c.id AND s.status<>'CANCELLED' "
            +"ORDER BY sold_on DESC,s.created_at DESC LIMIT 1) last_sale ON true "
            +"WHERE c.optic_id=? AND (lower(c.full_name) LIKE lower(?) OR c.cpf LIKE ? OR c.phone LIKE ?)"
            +(months==null?"":" AND last_sale.sold_on IS NOT NULL AND "
                +"(extract(year from age(?::date,last_sale.sold_on))*12"
                +" +extract(month from age(?::date,last_sale.sold_on)))::int=?")
            +" ORDER BY c.full_name LIMIT 300";
        String filter="%"+(q==null?"":q.trim())+"%";
        java.time.LocalDate today=clock.today(optic);
        return months==null?jdbc.queryForList(sql,today,today,optic,filter,filter,filter)
                :jdbc.queryForList(sql,today,today,optic,filter,filter,filter,today,today,months);
    }

    /** emailOptIn: o cliente autorizou receber mensagens (ex.: aniversário) por e-mail. */
    public record Customer(String fullName,String cpf,LocalDate birthDate,String phone,String address,
                           String email,Boolean emailOptIn){}
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody Customer req,Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_WRITE");
        UUID optic=context.optic(auth,session),id=UUID.randomUUID();
        validate(req);
        String cpf=normalize(req.cpf());
        String email=email(req.email());
        boolean optIn=Boolean.TRUE.equals(req.emailOptIn())&&email!=null;
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,birth_date,phone,address,email,email_opt_in,email_opt_in_at) "
                +"VALUES(?,?,?,?,?,?,?,?,?,CASE WHEN ? THEN now() END)",optic,id,req.fullName().trim(),cpf,
                req.birthDate(),req.phone(),req.address(),email,optIn,optIn);
        return Map.of("id",id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String,Boolean> update(@PathVariable UUID id,@RequestBody Customer req,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_WRITE");
        validate(req);
        String email=email(req.email());
        // Sem informar o campo (telas antigas), a autorização atual é mantida; sem e-mail, não há autorização.
        int count=jdbc.update("UPDATE customers SET full_name=?,cpf=?,birth_date=?,phone=?,address=?,"
                +"email=coalesce(?,CASE WHEN ? THEN NULL ELSE email END),"
                +"email_opt_in=CASE WHEN ?::boolean IS NULL THEN email_opt_in AND coalesce(?,email) IS NOT NULL "
                +"ELSE ?::boolean AND coalesce(?,email) IS NOT NULL END,"
                +"email_opt_in_at=CASE WHEN ?::boolean AND NOT email_opt_in THEN now() "
                +"WHEN ?::boolean IS FALSE THEN NULL ELSE email_opt_in_at END,"
                +"updated_at=now() WHERE optic_id=? AND id=?",
                req.fullName().trim(),normalize(req.cpf()),req.birthDate(),req.phone(),req.address(),
                email,req.email()!=null,req.emailOptIn(),email,req.emailOptIn(),email,req.emailOptIn(),req.emailOptIn(),
                context.optic(auth,session),id);
        if(count==0)throw new java.util.NoSuchElementException("Cliente não encontrado.");
        return Map.of("updated",true);
    }

    private static void validate(Customer req) {
        if(req==null||req.fullName()==null||req.fullName().isBlank()||req.birthDate()==null || req.address()==null || req.address().isBlank() || req.cpf()==null || req.cpf().isBlank())
            throw new IllegalArgumentException("Informe nome, CPF, nascimento e endereço do cliente.");
        if(req.cpf()!=null&&!req.cpf().isBlank()&&!normalize(req.cpf()).matches("\\d{11}"))
            throw new IllegalArgumentException("CPF inválido.");
    }
    public static String email(String value) {
        if(value==null||value.isBlank())return null;
        String v=value.trim().toLowerCase();
        if(v.length()>254||!v.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new IllegalArgumentException("E-mail inválido.");
        return v;
    }
    private static String normalize(String cpf) {
        if(cpf==null||cpf.isBlank())return null;
        return cpf.replaceAll("\\D","");
    }
}
