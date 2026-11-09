package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public ReportController(JdbcTemplate jdbc,OpticContext context){
        this.jdbc=jdbc;this.context=context;
    }

    private static final String[] HEADERS={
      "Data","Cliente","Vendedor","Lente","Armação","Situação",
      "Forma de pagamento","Bruto","Taxas","Líquido"};

    private List<List<String>> rows(UUID optic,LocalDate from,LocalDate to){
        List<Map<String,Object>> records=jdbc.queryForList(
          "SELECT s.sold_on,c.full_name AS customer,a.full_name AS seller,"
          +"l.brand AS lens,f.brand AS frame,s.status,s.payment_method,"
          +"s.charged_amount,s.fee_amount,s.net_amount FROM sales s "
          +"JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id "
          +"JOIN accounts a ON a.id=s.seller_id "
          +"JOIN catalog_items l ON l.optic_id=s.optic_id AND l.id=s.lens_id "
          +"JOIN catalog_items f ON f.optic_id=s.optic_id AND f.id=s.frame_id "
          +"WHERE s.optic_id=? AND (?::date IS NULL OR s.sold_on>=?::date) "
          +"AND (?::date IS NULL OR s.sold_on<=?::date) "
          +"ORDER BY s.sold_on DESC,s.created_at DESC LIMIT 2500",
          optic,from,from,to,to);
        List<List<String>> result=new ArrayList<>();
        for(Map<String,Object> row:records){
            List<String> r=new ArrayList<>();
            for(String col:List.of("sold_on","customer","seller","lens","frame","status",
                    "payment_method","charged_amount","fee_amount","net_amount"))
                r.add(row.get(col)==null?"":row.get(col).toString());
            result.add(r);
        }
        return result;
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
          @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session){
        context.require(auth,session,"REPORTS_READ");
        List<List<String>> records=rows(context.optic(auth,session),from,to);
        StringBuilder sb=new StringBuilder("\uFEFF");
        sb.append(String.join(";",HEADERS)).append("\r\n");
        for(List<String> record:records) {
            for(int i=0;i<record.size();i++) {
                if(i>0)sb.append(';');
                sb.append(escapeCsv(record.get(i)));
            }
            sb.append("\r\n");
        }
        return file(sb.toString().getBytes(StandardCharsets.UTF_8),
                "text/csv;charset=UTF-8","optics-vendas.csv");
    }

    private static String escapeCsv(String input){
        String safe=input==null?"":input;
        if(!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0))>=0)safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")+"\"";
    }

    @GetMapping(value="/sales.xlsx",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> xlsx(@RequestParam(required=false) LocalDate from,
           @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session) throws IOException{
        context.require(auth,session,"REPORTS_READ");
        List<List<String>> records=rows(context.optic(auth,session),from,to);
        try(Workbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet sheet=book.createSheet("Vendas");
            CellStyle titleStyle=book.createCellStyle();
            Font bold=book.createFont();bold.setBold(true);titleStyle.setFont(bold);
            Row headings=sheet.createRow(0);
            for(int i=0;i<HEADERS.length;i++){
                Cell cell=headings.createCell(i);
                cell.setCellValue(HEADERS[i]);cell.setCellStyle(titleStyle);
            }
            int rowNo=1;
            for(List<String> record:records){
                Row row=sheet.createRow(rowNo++);
                for(int i=0;i<record.size();i++)row.createCell(i).setCellValue(record.get(i));
            }
            for(int i=0;i<HEADERS.length;i++)sheet.setColumnWidth(i,Math.min(7000,HEADERS[i].length()*300+2000));
            book.write(out);
            return file(out.toByteArray(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "optics-vendas.xlsx");
        }
    }

    @GetMapping(value="/sales.pdf",produces="application/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(required=false) LocalDate from,
          @RequestParam(required=false) LocalDate to,Authentication auth,HttpSession session) throws IOException{
        context.require(auth,session,"REPORTS_READ");
        UUID optic=context.optic(auth,session);
        List<List<String>> records=rows(optic,from,to);
        String shop=jdbc.queryForObject("SELECT trade_name FROM optics WHERE id=?",String.class,optic);
        try(PDDocument pdf=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            PDType1Font title=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page=null;
            PDPageContentStream canvas=null;
            float y=0;
            try {
                int pageNumber=0;
                for(int i=-1;i<records.size();i++){
                    if(page==null||y<80) {
                        if(canvas!=null)canvas.close();
                        page=new PDPage(PDRectangle.A4);pdf.addPage(page);
                        canvas=new PDPageContentStream(pdf,page);
                        y=790;pageNumber++;
                        line(canvas,title,16,42,y,"OPTICS MANAGEMENT | RELATORIO DE VENDAS");y-=25;
                        line(canvas,font,10,42,y,ascii(shop)+"  |  "+String.valueOf(from)+" a "+String.valueOf(to));y-=20;
                        line(canvas,font,9,42,y,"Data       Cliente                        Lente / Armacao             Valor bruto / liquido");
                        y-=18;
                    }
                    if(i>=0){
                        List<String> r=records.get(i);
                        String row=fit(r.get(0),12)+fit(r.get(1),29)
                                +fit(r.get(3)+" / "+r.get(4),27)
                                +fit(r.get(7)+" / "+r.get(9),23);
                        line(canvas,font,8,42,y,ascii(row));
                        y-=15;
                    }
                }
            } finally {
                if(canvas!=null)canvas.close();
            }
            pdf.save(output);
            return file(output.toByteArray(),"application/pdf","optics-vendas.pdf");
        }
    }

    private static String ascii(String value){
        String normalized=java.text.Normalizer.normalize(value==null?"":value,
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","");
        return normalized.replaceAll("[^\\x20-\\x7E]","?");
    }
    private static String fit(String s,int length){
        String v=ascii(s);
        return (v.length()>length-1?v.substring(0,length-1):v)
                + " ".repeat(Math.max(0,length-v.length()));
    }
    private static void line(PDPageContentStream canvas,PDType1Font font,int size,
                             float x,float y,String text) throws IOException {
        canvas.beginText();canvas.setFont(font,size);canvas.newLineAtOffset(x,y);
        canvas.showText(text);canvas.endText();
    }
    private static ResponseEntity<byte[]> file(byte[] content,String mime,String name){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition","attachment; filename="+name)
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Type",mime).body(content);
    }
}
