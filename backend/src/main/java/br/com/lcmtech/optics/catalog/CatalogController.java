package br.com.lcmtech.optics.catalog;

import br.com.lcmtech.optics.catalog.domain.CatalogCategory;
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
            return jdbc.queryForList("SELECT id,kind,brand,model,category,active FROM catalog_items "
                    +"WHERE optic_id=? ORDER BY kind,brand,model LIMIT 300",optic);
        return jdbc.queryForList("SELECT id,kind,brand,model,category,active FROM catalog_items "
                    +"WHERE optic_id=? AND kind=? ORDER BY brand,model LIMIT 300",optic,kind);
    }

    /** Opções de tipo de lente e categoria de armação para as telas. */
    @GetMapping("/categories")
    public Map<String,Map<String,String>> categories(Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_READ");
        return Map.of("LENS",CatalogCategory.LENS,"FRAME",CatalogCategory.FRAME);
    }

    public record CatalogItem(String kind,String brand,String model,String category){}
    @PostMapping
    public Map<String,Object> create(@RequestBody CatalogItem req,Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_WRITE");
        if(req==null||!List.of("LENS","FRAME").contains(req.kind())||
                req.brand()==null||req.brand().isBlank())
            throw new IllegalArgumentException("Informe lente ou armação e a marca.");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand,model,category) VALUES(?,?,?,?,?,?)",
                context.optic(auth,session),id,req.kind(),req.brand().trim(),blank(req.model()),
                CatalogCategory.require(req.kind(),req.category()));
        return Map.of("id",id);
    }

    public record CatalogEdit(String brand,String model,String category){}

    /** Corrige marca, modelo e tipo. As vendas apontam para o item, então os relatórios refletem a correção. */
    @PatchMapping("/{id}")
    public Map<String,Boolean> edit(@PathVariable UUID id,@RequestBody CatalogEdit req,
                                    Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_WRITE");
        UUID optic=context.optic(auth,session);
        List<String> kinds=jdbc.queryForList("SELECT kind FROM catalog_items WHERE optic_id=? AND id=?",
                String.class,optic,id);
        if(kinds.isEmpty())throw new java.util.NoSuchElementException("Produto não encontrado.");
        if(req==null||req.brand()==null||req.brand().isBlank())throw new IllegalArgumentException("Informe a marca.");
        jdbc.update("UPDATE catalog_items SET brand=?,model=?,category=? WHERE optic_id=? AND id=?",
                req.brand().trim(),blank(req.model()),CatalogCategory.require(kinds.get(0),req.category()),optic,id);
        return Map.of("updated",true);
    }

    private static String blank(String value){
        return value==null||value.isBlank()?null:value.trim();
    }

    @PatchMapping("/{id}/active")
    public Map<String,Boolean> active(@PathVariable UUID id,@RequestBody Map<String,Boolean> request,
                                      Authentication auth,HttpSession session) {
        context.require(auth,session,"CATALOG_WRITE");
        if(request.get("active")==null)throw new IllegalArgumentException("Informe active.");
        int n=jdbc.update("UPDATE catalog_items SET active=? WHERE optic_id=? AND id=?",
                request.get("active"),context.optic(auth,session),id);
        if(n==0)throw new java.util.NoSuchElementException("Produto não encontrado.");
        return Map.of("updated",true);
    }
}
