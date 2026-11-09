package br.com.lcmtech.optics.customers;

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
    public CustomerController(JdbcTemplate jdbc,OpticContext context) {
        this.jdbc=jdbc;this.context=context;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(defaultValue="") String q,
             @RequestParam(required=false) Integer months,
             Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_READ");
        if(months!=null&&!List.of(3,6,9,12).contains(months))
            throw new IllegalArgumentException("Escolha 3, 6, 9 ou 12 meses.");
        UUID optic=context.optic(auth,session);
        String sql="SELECT c.id,c.full_name,c.cpf,c.birth_date,c.phone,c.address,c.created_at,"
            +"last_sale.sold_on AS last_sale_on,last_sale.replacement_months,"
            +"(extract(year from age(current_date,last_sale.sold_on))*12"
            +" +extract(month from age(current_date,last_sale.sold_on)))::int AS months_since_sale "
            +"FROM customers c LEFT JOIN LATERAL (SELECT sold_on,replacement_months FROM sales s "
            +"WHERE s.optic_id=c.optic_id AND s.customer_id=c.id AND s.status<>'CANCELLED' "
            +"ORDER BY sold_on DESC,s.created_at DESC LIMIT 1) last_sale ON true "
            +"WHERE c.optic_id=? AND (lower(c.full_name) LIKE lower(?) OR c.cpf LIKE ? OR c.phone LIKE ?)"
            +(months==null?"":" AND last_sale.sold_on IS NOT NULL AND "
                +"(extract(year from age(current_date,last_sale.sold_on))*12"
                +" +extract(month from age(current_date,last_sale.sold_on)))::int=?")
            +" ORDER BY c.full_name LIMIT 300";
        String filter="%"+(q==null?"":q.trim())+"%";
        return months==null?jdbc.queryForList(sql,optic,filter,filter,filter)
                :jdbc.queryForList(sql,optic,filter,filter,filter,months);
    }

    public record Customer(String fullName,String cpf,LocalDate birthDate,String phone,String address){}
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody Customer req,Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_WRITE");
        UUID optic=context.optic(auth,session),id=UUID.randomUUID();
        validate(req);
        String cpf=normalize(req.cpf());
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,birth_date,phone,address) "
                +"VALUES(?,?,?,?,?,?,?)",optic,id,req.fullName().trim(),cpf,
                req.birthDate(),req.phone(),req.address());
        return Map.of("id",id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String,Boolean> update(@PathVariable UUID id,@RequestBody Customer req,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"CUSTOMERS_WRITE");
        validate(req);
        int count=jdbc.update("UPDATE customers SET full_name=?,cpf=?,birth_date=?,phone=?,address=?,"
                +"updated_at=now() WHERE optic_id=? AND id=?",
                req.fullName().trim(),normalize(req.cpf()),req.birthDate(),req.phone(),req.address(),
                context.optic(auth,session),id);
        if(count==0)throw new IllegalArgumentException("Cliente não encontrado.");
        return Map.of("updated",true);
    }

    private static void validate(Customer req) {
        if(req==null||req.fullName()==null||req.fullName().isBlank()||req.birthDate()==null)
            throw new IllegalArgumentException("Informe nome e nascimento do cliente.");
        if(req.cpf()!=null&&!req.cpf().isBlank()&&!normalize(req.cpf()).matches("\\d{11}"))
            throw new IllegalArgumentException("CPF inválido.");
    }
    private static String normalize(String cpf) {
        if(cpf==null||cpf.isBlank())return null;
        return cpf.replaceAll("\\D","");
    }
}
