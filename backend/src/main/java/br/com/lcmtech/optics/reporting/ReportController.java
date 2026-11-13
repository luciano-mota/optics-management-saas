package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Desempenho da equipe e exportação de vendas (atalho para o relatório detalhado de vendas). */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final DetailedReportController detailed;
    public ReportController(JdbcTemplate jdbc,OpticContext context,DetailedReportController detailed){
        this.jdbc=jdbc;this.context=context;this.detailed=detailed;
    }

    @GetMapping("/performance")
    public Map<String,Object> performance(Authentication auth,HttpSession session){
        context.require(auth,session,"REPORTS_READ");
        UUID optic=context.optic(auth,session);
        List<Map<String,Object>> staff=jdbc.queryForList("SELECT a.full_name AS employee,"
            +"count(DISTINCT s.id) AS sales_count,coalesce(sum(s.charged_amount),0) AS sales_gross "
            +"FROM optic_memberships m JOIN accounts a ON a.id=m.account_id "
            +"LEFT JOIN sales s ON s.optic_id=m.optic_id AND s.seller_id=m.account_id "
            +"AND s.status<>'CANCELLED' WHERE m.optic_id=? GROUP BY a.id,a.full_name "
            +"ORDER BY sales_count DESC",optic);
        List<Map<String,Object>> visits=jdbc.queryForList(
            "SELECT a.full_name AS employee,"
            +"count(v.id) AS assigned,count(v.id) FILTER(WHERE v.status IN "
            +"('VISITED','CONVERTED','NOT_CONVERTED')) AS attended,"
            +"count(v.id) FILTER(WHERE v.status='CONVERTED') AS converted "
            +"FROM optic_memberships m JOIN accounts a ON a.id=m.account_id "
            +"LEFT JOIN home_visits v ON v.optic_id=m.optic_id AND v.assigned_to=m.account_id "
            +"WHERE m.optic_id=? GROUP BY a.id,a.full_name ORDER BY assigned DESC",optic);
        return Map.of("salesByEmployee",staff,"visitsByEmployee",visits);
    }

    @GetMapping(value="/sales.csv",produces="text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> csv(@RequestParam(required=false) LocalDate from,
          @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session) throws IOException{
        return detailed.export("vendas","csv",from,to,null,null,null,auth,session);
    }

    @GetMapping(value="/sales.xlsx",produces=TabularReport.XLSX)
    public ResponseEntity<byte[]> xlsx(@RequestParam(required=false) LocalDate from,
           @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session) throws IOException{
        return detailed.export("vendas","xlsx",from,to,null,null,null,auth,session);
    }

    @GetMapping(value="/sales.pdf",produces="application/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(required=false) LocalDate from,
          @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session) throws IOException{
        return detailed.export("vendas","pdf",from,to,null,null,null,auth,session);
    }
}
