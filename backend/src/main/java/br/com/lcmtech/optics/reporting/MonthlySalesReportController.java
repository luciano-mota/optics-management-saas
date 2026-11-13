package br.com.lcmtech.optics.reporting;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * O único motor de cálculo é MonthlySalesSummaryService. Formatos distintos
 * exportam o mesmo conjunto ordenado de 14 indicadores.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class MonthlySalesReportController {
    private final OpticContext access;
    private final MonthlySalesSummaryService engine;
    private final JdbcTemplate jdbc;
    private final IndicatorDetailService details;
    private static final Locale PT=Locale.forLanguageTag("pt-BR");

    public MonthlySalesReportController(OpticContext access,MonthlySalesSummaryService engine,
            JdbcTemplate jdbc,IndicatorDetailService details) {
        this.access=access;this.engine=engine;this.jdbc=jdbc;this.details=details;
    }

    private record Context(UUID optic,MonthlySalesSummaryService.Summary data) {}

    private Context load(Authentication auth,HttpSession session,int year,int month,LocalDate asOf) {
        access.require(auth,session,"REPORTS_READ");
        access.require(auth,session,"FINANCE_READ");
        UUID optic=access.optic(auth,session);
        return new Context(optic,engine.compute(optic,year,month,asOf));
    }

    @GetMapping("/monthly-sales-summary")
    public Map<String,Object> summary(@RequestParam int year,@RequestParam int month,
            @RequestParam LocalDate asOf,Authentication auth,HttpSession session) {
        var result=load(auth,session,year,month,asOf).data();
        return Map.of("summary",result,"indicators",engine.metrics(result));
    }

    /** A lista que forma um indicador (soma igual ao número do resumo). */
    @GetMapping("/monthly-sales-summary/detail/{kind}")
    public Map<String,Object> detail(@PathVariable String kind,@RequestParam int year,@RequestParam int month,
            @RequestParam LocalDate asOf,Authentication auth,HttpSession session) {
        access.require(auth,session,"REPORTS_READ");
        access.require(auth,session,"FINANCE_READ");
        TabularReport t=details.detail(access.optic(auth,session),year,month,asOf,kind);
        return Map.of("title",t.title(),"headers",t.headers(),"money",t.money(),"rows",t.rows(),
                "summary",t.summary());
    }

    @GetMapping("/monthly-sales-summary/detail/{kind}/export/{format}")
    public ResponseEntity<byte[]> detailExport(@PathVariable String kind,@PathVariable String format,
            @RequestParam int year,@RequestParam int month,@RequestParam LocalDate asOf,
            Authentication auth,HttpSession session) throws IOException {
        access.require(auth,session,"REPORTS_READ");
        access.require(auth,session,"FINANCE_READ");
        UUID optic=access.optic(auth,session);
        TabularReport t=details.detail(optic,year,month,asOf,kind);
        String shop=jdbc.queryForObject("SELECT trade_name FROM optics WHERE id=?",String.class,optic);
        String period=String.format("%02d/%d - corte em %s",month,year,
                asOf.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        return switch(format){
            case "csv" -> attachment(t.csv(),"text/csv;charset=UTF-8",t.fileBase()+".csv");
            case "xlsx" -> attachment(t.xlsx(),TabularReport.XLSX,t.fileBase()+".xlsx");
            case "pdf" -> attachment(t.pdf(shop,period),"application/pdf",t.fileBase()+".pdf");
            default -> throw new IllegalArgumentException("Formato inválido. Use pdf, xlsx ou csv.");
        };
    }

    private static String display(BigDecimal amount,boolean money) {
        if(amount==null)return "—";
        if(!money)return String.valueOf(amount.intValueExact());
        return NumberFormat.getCurrencyInstance(PT).format(amount);
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes,String type,String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition","attachment; filename=\""+name+"\"")
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Type",type)
                .body(bytes);
    }

    private static String filename(MonthlySalesSummaryService.Summary s,String extension) {
        return "optics-vendas-atualizadas-"+s.year()+"-"+String.format("%02d",s.month())
                +"-"+s.asOf()+extension;
    }

    @GetMapping(value="/monthly-sales-summary.csv",produces="text/csv")
    public ResponseEntity<byte[]> csv(@RequestParam int year,@RequestParam int month,
            @RequestParam LocalDate asOf,Authentication auth,HttpSession session) {
        var s=load(auth,session,year,month,asOf).data();
        StringBuilder csv=new StringBuilder("\uFEFF");
        csv.append("Indicador;Valor\r\n");
        for(var item:engine.metrics(s)) {
            String text=display(item.value(),item.money()).replace("\"","\"\"");
            csv.append("\"").append(item.name().replace("\"","\"\"")).append("\";\"")
                    .append(text).append("\"\r\n");
        }
        return attachment(csv.toString().getBytes(StandardCharsets.UTF_8),
                "text/csv;charset=UTF-8",filename(s,".csv"));
    }

    @GetMapping(value="/monthly-sales-summary.xlsx",
                produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> xlsx(@RequestParam int year,@RequestParam int month,
            @RequestParam LocalDate asOf,Authentication auth,HttpSession session) throws IOException {
        var s=load(auth,session,year,month,asOf).data();
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("Vendas atualizadas");
            var bold=workbook.createFont();bold.setBold(true);bold.setFontHeightInPoints((short)12);
            var header=workbook.createCellStyle();header.setFont(bold);
            header.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{(byte)23,(byte)21,(byte)58},null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var light=workbook.createFont();light.setColor(IndexedColors.WHITE.getIndex());
            light.setBold(true);header.setFont(light);
            var money=workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("\"R$\" #,##0.00"));
            var title=sheet.createRow(0);title.createCell(0).setCellValue("VENDAS ATUALIZADAS");
            title.getCell(0).setCellStyle(header);
            sheet.createRow(1).createCell(0).setCellValue(s.opticName());
            sheet.createRow(2).createCell(0).setCellValue("Data do fechamento: "+s.asOf());
            var headings=sheet.createRow(4);
            headings.createCell(0).setCellValue("Indicador");
            headings.createCell(1).setCellValue("Valor");
            headings.getCell(0).setCellStyle(header);headings.getCell(1).setCellStyle(header);
            int n=5;
            for(var item:engine.metrics(s)) {
                var row=sheet.createRow(n++);
                row.createCell(0).setCellValue(item.name());
                var cell=row.createCell(1);
                if(item.value()!=null) {
                    cell.setCellValue(item.value().doubleValue());
                    if(item.money())cell.setCellStyle(money);
                }else cell.setCellValue("—");
            }
            sheet.setColumnWidth(0,10500);sheet.setColumnWidth(1,4800);
            sheet.createFreezePane(0,5);
            var details=workbook.createSheet("Memória de cálculo");
            String[][] notes={
                {"Loja",s.opticName()},
                {"Período",s.year()+"-"+String.format("%02d",s.month())},
                {"Data de corte",s.asOf().toString()},
                {"Base da meta","Valor comercial bruto das vendas do mês até o corte"},
                {"Venda líquida","Recebíveis de vendas liquidados até a data de corte"},
                {"Vendas a receber","Recebíveis de vendas ainda não liquidados no corte"},
                {"Taxas","Taxas do cartão do pedido, uma vez por venda"},
                {"Dias trabalhados","Dias de funcionamento da loja até o corte, mesmo sem venda"},
                {"Conciliação cobrado - recebido - pendente - taxas",
                    s.reconciliationDifference().toPlainString()},
                {"Meta configurada",s.goalConfigured()?"Sim":"Não"},
                {"Calendário revisado pelo Master",s.calendarReviewed()?"Sim":"Não"},
                {"Feriados locais verificados",s.localHolidaysVerified()?"Sim":"Não"}
            };
            int i=0;
            for(String[] pair:notes){
                var row=details.createRow(i++);
                row.createCell(0).setCellValue(pair[0]);row.createCell(1).setCellValue(pair[1]);
            }
            i++;
            for(String warning:s.warnings()){
                var row=details.createRow(i++);row.createCell(0).setCellValue("Atenção");
                row.createCell(1).setCellValue(warning);
            }
            details.setColumnWidth(0,15500);details.setColumnWidth(1,24000);
            workbook.write(output);
            return attachment(output.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    filename(s,".xlsx"));
        }
    }

    @GetMapping(value="/monthly-sales-summary.pdf",produces="application/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam int year,@RequestParam int month,
            @RequestParam LocalDate asOf,Authentication auth,HttpSession session) throws IOException {
        var context=load(auth,session,year,month,asOf);
        var s=context.data();
        try(var pdf=new PDDocument();var output=new ByteArrayOutputStream()) {
            PDPage page=new PDPage(PDRectangle.A4);pdf.addPage(page);
            var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try(var canvas=new PDPageContentStream(pdf,page)) {
                canvas.setNonStrokingColor(new Color(23,21,58));
                canvas.addRect(0,706,PDRectangle.A4.getWidth(),136);canvas.fill();
                canvas.setNonStrokingColor(Color.WHITE);
                line(canvas,bold,19,46,790,"VENDAS ATUALIZADAS");
                line(canvas,regular,10,46,763,"OPTICS MANAGEMENT  |  RESUMO GERENCIAL");
                String shop=ascii(s.opticName());
                line(canvas,bold,12,46,739,fit(shop,58));
                line(canvas,regular,10,46,721,"DATA: "+s.asOf()+"  |  MES: "+s.month()+"/"+s.year());
                try {
                    List<byte[]> logos=jdbc.query(
                        "SELECT logo FROM site_settings WHERE optic_id=? AND logo IS NOT NULL",
                        (rs,n)->rs.getBytes(1),context.optic());
                    if(!logos.isEmpty()){
                        var image=PDImageXObject.createFromByteArray(pdf,logos.get(0),"logo-otica");
                        float ratio=(float)image.getWidth()/Math.max(1,image.getHeight());
                        float width=Math.min(85,80*ratio);float height=width/ratio;
                        canvas.drawImage(image,PDRectangle.A4.getWidth()-45-width,740,width,height);
                    }
                } catch(Exception ignored) {
                    // Falha da imagem não impede emissão do relatório.
                }
                canvas.setNonStrokingColor(new Color(36,35,61));
                line(canvas,bold,11,46,673,"INDICADORES DO MES");
                float y=652;
                int i=0;
                for(var item:engine.metrics(s)) {
                    if(i%2==0) {
                        canvas.setNonStrokingColor(new Color(246,246,250));
                        canvas.addRect(44,y-9,506,23);canvas.fill();
                    }
                    canvas.setNonStrokingColor(new Color(36,35,61));
                    line(canvas,regular,10,53,y,ascii(item.name()));
                    line(canvas,bold,10,390,y,ascii(display(item.value(),item.money())));
                    y-=27;i++;
                }
                y-=17;
                canvas.setNonStrokingColor(new Color(23,21,58));
                line(canvas,bold,10,46,y,"CONFERENCIA DOS VALORES");
                y-=17;
                canvas.setNonStrokingColor(new Color(36,35,61));
                line(canvas,regular,9,46,y,"Diferenca cobrado - recebido - pendente - taxas: "
                        +ascii(display(s.reconciliationDifference(),true)));
                y-=16;
                if(!s.warnings().isEmpty()) {
                    line(canvas,regular,8,46,y,"ATENCAO: "+fit(ascii(s.warnings().get(0)),85));
                }
                canvas.setNonStrokingColor(new Color(107,106,133));
                line(canvas,regular,8,46,36,"Documento gerencial - nao substitui comprovante de pagamento nem documento fiscal.");
            }
            pdf.save(output);
            return attachment(output.toByteArray(),"application/pdf",filename(s,".pdf"));
        }
    }

    private static String ascii(String s) {
        if(s==null)return "";
        return Normalizer.normalize(s,Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").replaceAll("[^\\x20-\\x7E]","?");
    }

    private static String fit(String s,int max) {
        return s.length()>max?s.substring(0,max-3)+"...":s;
    }

    private static void line(PDPageContentStream out,PDType1Font font,int size,
                             float x,float y,String str) throws IOException {
        out.beginText();out.setFont(font,size);out.newLineAtOffset(x,y);
        out.showText(str);out.endText();
    }
}
