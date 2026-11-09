package br.com.lcmtech.optics.catalog;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public CatalogController(JdbcTemplate jdbc,OpticContext context) {
        this.jdbc=jdbc;this.context=context;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(required=false) String kind,
             Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_READ");
        UUID optic=context.optic(auth,session);
        if(kind==null||kind.isBlank())
            return jdbc.queryForList("SELECT id,kind,brand,model,active FROM catalog_items "
                    +"WHERE optic_id=? ORDER BY kind,brand,model LIMIT 300",optic);
        return jdbc.queryForList("SELECT id,kind,brand,model,active FROM catalog_items "
                    +"WHERE optic_id=? AND kind=? ORDER BY brand,model LIMIT 300",optic,kind);
    }

    public record CatalogItem(String kind,String brand,String model){}
    @PostMapping
    public Map<String,Object> create(@RequestBody CatalogItem req,Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_WRITE");
        if(req==null||!List.of("LENS","FRAME").contains(req.kind())||
                req.brand()==null||req.brand().isBlank())
            throw new IllegalArgumentException("Informe lente ou armação e a marca.");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand,model) VALUES(?,?,?,?,?)",
                context.optic(auth,session),id,req.kind(),req.brand().trim(),req.model());
        return Map.of("id",id);
    }

    @PatchMapping("/{id}/active")
    public Map<String,Boolean> active(@PathVariable UUID id,@RequestBody Map<String,Boolean> request,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_WRITE");
        if(request.get("active")==null)throw new IllegalArgumentException("Informe active.");
        int n=jdbc.update("UPDATE catalog_items SET active=? WHERE optic_id=? AND id=?",
                request.get("active"),context.optic(auth,session),id);
        if(n==0)throw new IllegalArgumentException("Produto não encontrado.");
        return Map.of("updated",true);
    }
}
