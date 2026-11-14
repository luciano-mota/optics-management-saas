package br.com.lcmtech.optics.identity;

import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.OpticLocationService;
import br.com.lcmtech.optics.shop.OpticProfileController.AddressInput;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** No primeiro acesso o titular confirma dados pessoais; a ótica é entidade distinta. */
@RestController
@RequestMapping("/api/v1/auth/profile")
public class ProfileController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final OpticLocationService locations;
    public ProfileController(JdbcTemplate jdbc,OpticContext context,OpticLocationService locations) {
        this.jdbc=jdbc;this.context=context;this.locations=locations;
    }
    /** opticAddress: endereço da ótica com CEP, exigido do Master enquanto não houver um salvo. */
    public record Profile(String fullName,String cpf,String phone,LocalDate birthDate,
                          String address,String opticCnpj,AddressInput opticAddress) {}

    @GetMapping
    public Map<String,Object> get(Authentication authentication) {
        UUID account=context.accountId(authentication);
        return jdbc.queryForMap("""
            SELECT id,email,coalesce(full_name,'') AS full_name,
               coalesce(cpf,'') AS cpf,coalesce(phone,'') AS phone,
               birth_date,coalesce(address,'') AS address,
               profile_completed_at IS NOT NULL AS profile_complete
            FROM accounts WHERE id=?
            """,account);
    }

    @PutMapping
    @Transactional
    public Map<String,Boolean> update(@RequestBody Profile input,
                                      Authentication authentication,HttpSession session) {
        if(input==null||input.fullName()==null||input.fullName().trim().length()<3
                ||input.fullName().trim().length()>180)
            throw new IllegalArgumentException("Informe seu nome completo.");
        String cpf=digits(input.cpf()),phone=digits(input.phone());
        if(!validCpf(cpf))
            throw new IllegalArgumentException("O CPF informado não é válido.");
        if(phone.length()<10||phone.length()>13)
            throw new IllegalArgumentException("Informe telefone com DDD (10 a 13 números).");
        if(input.birthDate()==null || input.birthDate().isAfter(LocalDate.now())
                ||input.birthDate().isBefore(LocalDate.of(1900,1,1)))
            throw new IllegalArgumentException("Informe uma data de nascimento válida.");
        if(input.address()!=null && input.address().length()>1000)
            throw new IllegalArgumentException("Endereço muito longo.");
        UUID account=context.accountId(authentication);
        Integer duplicate=jdbc.queryForObject("""
            SELECT count(*) FROM accounts WHERE cpf=? AND id<>? AND active
            """,Integer.class,cpf,account);
        if(duplicate!=null&&duplicate>0)
            throw new IllegalArgumentException("CPF já cadastrado. Entre em contato com o suporte.");
        // Apenas o Master preenche o CNPJ; colaboradores e o operador LCM
        // continuam identificados por CPF pessoal, sem CNPJ obrigatório.
        UUID managedOptic=null;
        var shops=context.memberships(account);
        if(!shops.isEmpty()) {
            UUID optic=context.optic(authentication,session);
            if("MASTER".equals(context.role(account,optic)))managedOptic=optic;
        }
        if(managedOptic!=null) {
            String current=jdbc.queryForObject(
                    "SELECT tax_document FROM optics WHERE id=? FOR UPDATE",
                    String.class,managedOptic);
            String value=input.opticCnpj()==null||input.opticCnpj().isBlank()?
                    BrazilianTaxId.normalizeCnpj(current):
                    BrazilianTaxId.normalizeCnpj(input.opticCnpj());
            if(!BrazilianTaxId.validCnpj(value))
                throw new IllegalArgumentException("Informe o CNPJ válido da ótica.");
            if(!value.equals(current)) {
                jdbc.update("UPDATE optics SET tax_document=?,updated_at=now() WHERE id=?",
                        value,managedOptic);
                jdbc.update("""
                    INSERT INTO audit_events(id,optic_id,actor_account_id,
                        event_type,entity_type,entity_id)
                    VALUES(?,?,?,'OPTIC_CNPJ_REGISTERED','OPTIC',?)
                    """,UUID.randomUUID(),managedOptic,account,managedOptic);
            }
            // O endereço da ótica alimenta o calendário (cidade, UF, fuso) automaticamente.
            if(input.opticAddress()!=null)
                locations.save(managedOptic,account,input.opticAddress().toLocation());
            else if(!locations.hasAddress(managedOptic))
                throw new IllegalArgumentException("Informe o endereço da ótica.");
        }
        jdbc.update("""
            UPDATE accounts SET full_name=?,cpf=?,phone=?,birth_date=?,address=?,
                profile_completed_at=COALESCE(profile_completed_at,now()),updated_at=now()
            WHERE id=?
            """,input.fullName().trim(),cpf,phone,input.birthDate(),
              input.address()==null?null:input.address().trim(),account);
        jdbc.update("""
            INSERT INTO audit_events(id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,'ACCOUNT_PROFILE_COMPLETED_OR_UPDATED','ACCOUNT',?)
            """,UUID.randomUUID(),account,account);
        return Map.of("saved",true,"profileComplete",true);
    }

    private static String digits(String value) {
        return value==null?"":value.replaceAll("[^0-9]","");
    }
    /** Dois dígitos verificadores (mod 11) do CPF. */
    public static boolean validCpf(String cpf) {
        if(!cpf.matches("[0-9]{11}")||cpf.chars().distinct().count()==1)return false;
        for(int length=9;length<=10;length++) {
            int sum=0;
            for(int i=0;i<length;i++)sum+=(cpf.charAt(i)-'0')*(length+1-i);
            int check=11-(sum%11);
            if(check>=10)check=0;
            if(check!=cpf.charAt(length)-'0')return false;
        }
        return true;
    }
}
