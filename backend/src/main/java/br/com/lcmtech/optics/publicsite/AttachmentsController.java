package br.com.lcmtech.optics.publicsite;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentsController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public AttachmentsController(JdbcTemplate jdbc,OpticContext context) {
        this.jdbc=jdbc;this.context=context;
    }

    public record Attachment(String filename,String mime,String dataBase64){}

    @PostMapping("/customers/{customerId}")
    public Map<String,Boolean> add(@PathVariable UUID customerId,@RequestBody Attachment payload,
                                   Authentication auth,HttpSession session){
        context.require(auth,session,"CUSTOMERS_WRITE");
        UUID optic=context.optic(auth,session);
        Integer n=jdbc.queryForObject("SELECT count(*) FROM customers WHERE optic_id=? AND id=?",
                Integer.class,optic,customerId);
        if(n==null||n==0)throw new IllegalArgumentException("Cliente não encontrado.");
        AttachmentCodec.store(jdbc,optic,customerId,null,null,payload.filename(),
                payload.mime(),payload.dataBase64());
        return Map.of("uploaded",true);
    }

    @GetMapping("/customers/{customerId}")
    public List<Map<String,Object>> customerFiles(@PathVariable UUID customerId,
                    Authentication auth,HttpSession session){
        context.require(auth,session,"CUSTOMERS_READ");
        return jdbc.queryForList("SELECT id,original_name,content_type,created_at FROM file_attachments "
                +"WHERE optic_id=? AND customer_id=? ORDER BY created_at DESC",
                context.optic(auth,session),customerId);
    }

    @GetMapping("/visits/{visitId}")
    public List<Map<String,Object>> visitFiles(@PathVariable UUID visitId,
                    Authentication auth,HttpSession session){
        context.require(auth,session,"VISITS_READ");
        return jdbc.queryForList("SELECT id,original_name,content_type,created_at FROM file_attachments "
                +"WHERE optic_id=? AND visit_id=? ORDER BY created_at DESC",
                context.optic(auth,session),visitId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,Authentication auth,HttpSession session){
        UUID optic=context.optic(auth,session),account=context.accountId(auth);
        boolean canReadCustomers=context.can(account,optic,"CUSTOMERS_READ");
        boolean canReadVisits=context.can(account,optic,"VISITS_READ");
        if(!canReadCustomers&&!canReadVisits)
            throw new org.springframework.security.access.AccessDeniedException("Sem acesso a receitas.");
        Map<String,Object> file=jdbc.queryForList("SELECT original_name,content_type,bytes,customer_id,visit_id "
                +"FROM file_attachments WHERE optic_id=? AND id=?",optic,id)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("Arquivo não encontrado."));
        if((file.get("customer_id")!=null&&!canReadCustomers) ||
                (file.get("visit_id")!=null&&!canReadVisits))
            throw new org.springframework.security.access.AccessDeniedException("Sem permissão para esta receita.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=receita")
                .contentType(MediaType.parseMediaType((String)file.get("content_type")))
                .body((byte[])file.get("bytes"));
    }
}
