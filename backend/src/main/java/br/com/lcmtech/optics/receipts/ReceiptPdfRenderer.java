package br.com.lcmtech.optics.receipts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** O PDF final é guardado em bytes no momento da emissão: não muda no futuro. */
public final class ReceiptPdfRenderer {
    private ReceiptPdfRenderer() {}

    public record Details(
            UUID id,long number,String shopName,String legalName,String cnpj,
            String address,String contactPhone,String customerName,String customerCpf,
            String lens,String frame,String paymentMethod,LocalDate soldOn,LocalDate paidOn,
            BigDecimal paymentGross,BigDecimal fee,BigDecimal net,BigDecimal pending,
            String signerName,String signerRole,byte[] logo,byte[] signature) {}

    private static String money(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
                .format(value==null?BigDecimal.ZERO:value);
    }

    public static byte[] render(Details d) throws IOException {
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            var page=new PDPage(PDRectangle.A4);doc.addPage(page);
            var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try(var canvas=new PDPageContentStream(doc,page)) {
                canvas.setNonStrokingColor(new Color(15,91,96));
                canvas.addRect(0,711,PDRectangle.A4.getWidth(),131);canvas.fill();
                canvas.setNonStrokingColor(Color.WHITE);
                line(canvas,bold,18,43,797,"RECIBO DE PAGAMENTO");
                line(canvas,regular,11,43,777,"OPTICS MANAGEMENT  |  COMPROVANTE DA OTICA");
                line(canvas,bold,12,43,745,d.shopName());
                line(canvas,regular,9,43,726,"Recibo N. "+d.number()+"  |  Pagamento: "+d.paidOn());
                if(d.logo()!=null) {
                    try {
                        PDImageXObject image=PDImageXObject.createFromByteArray(doc,d.logo(),"logo");
                        float ratio=(float)image.getWidth()/Math.max(image.getHeight(),1);
                        float width=Math.min(90,80*ratio),height=Math.min(72,width/Math.max(0.01f,ratio));
                        canvas.drawImage(image,PDRectangle.A4.getWidth()-45-width,745,width,height);
                    } catch(Exception ignored) { /* Identidade em texto permanece válida. */ }
                }

                canvas.setNonStrokingColor(new Color(38,58,65));
                float y=681;
                line(canvas,bold,12,44,y,"IDENTIFICACAO");y-=22;
                line(canvas,regular,10,44,y,"Emitente: "+d.legalName());y-=20;
                line(canvas,regular,10,44,y,"CNPJ: "+formatCnpj(d.cnpj()));y-=20;
                line(canvas,regular,10,44,y,"Endereco: "+d.address());y-=20;
                line(canvas,regular,10,44,y,"Contato: "+d.contactPhone());y-=23;
                line(canvas,bold,11,44,y,"CLIENTE E PEDIDO");y-=21;
                line(canvas,regular,10,44,y,"Cliente: "+d.customerName());y-=19;
                line(canvas,regular,10,44,y,"CPF: "+maskCpf(d.customerCpf()));y-=19;
                line(canvas,regular,10,44,y,"Data da venda: "+d.soldOn());y-=19;
                line(canvas,regular,10,44,y,"Lentes: "+d.lens());y-=19;
                line(canvas,regular,10,44,y,"Armacao: "+d.frame());y-=25;

                canvas.setNonStrokingColor(new Color(238,247,245));
                canvas.addRect(39,y-70,517,93);canvas.fill();
                canvas.setNonStrokingColor(new Color(25,72,74));
                line(canvas,bold,12,48,y+3,"VALORES DO PAGAMENTO");y-=22;
                line(canvas,regular,10,48,y,"Forma: "+d.paymentMethod());y-=18;
                line(canvas,bold,14,48,y,"Valor pago pelo cliente: "+money(d.paymentGross()));y-=18;
                line(canvas,regular,9,48,y,"Taxas da operacao: "+money(d.fee())
                        +"  |  Liquido da otica: "+money(d.net()));y-=40;
                if(d.pending().signum()>0) {
                    line(canvas,bold,10,46,y,"SALDO EM ABERTO: "+money(d.pending()));y-=19;
                    line(canvas,regular,9,46,y,
                        "Este recibo comprova somente o pagamento indicado, sem quitacao do saldo.");y-=30;
                } else {
                    line(canvas,bold,10,46,y,"Sem parcelas em aberto para esta venda.");y-=28;
                }

                if(d.signature()!=null) {
                    try {
                        var sign=PDImageXObject.createFromByteArray(doc,d.signature(),"assinatura");
                        float ratio=(float)sign.getWidth()/Math.max(1,sign.getHeight());
                        float w=Math.min(158,64*ratio),h=Math.min(64,w/Math.max(0.01f,ratio));
                        canvas.drawImage(sign,49,y-65,w,h);
                    } catch(Exception ignored) { /* Assinatura de imagem opcional. */ }
                }
                y-=74;
                line(canvas,bold,10,47,y,d.signerName()==null?
                        "Responsavel pela emissao":d.signerName());
                y-=15;
                line(canvas,regular,9,47,y,d.signerRole()==null?
                        "Representante da otica":d.signerRole());
                y-=20;
                line(canvas,regular,8,47,y,"Assinatura cadastrada e apenas representacao grafica, nao certificado ICP-Brasil.");

                canvas.setNonStrokingColor(new Color(98,112,116));
                line(canvas,regular,8,44,58,"Documento de pagamento emitido pela otica. Nao substitui nota fiscal.");
                line(canvas,regular,8,44,42,"Identificacao unica: "+d.id());
            }
            doc.save(output);
            return output.toByteArray();
        }
    }

    private static String safe(String s) {
        if(s==null||s.isBlank())return "Nao informado";
        String plain=Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","");
        plain=plain.replaceAll("[^\\x20-\\x7E]","?");
        return plain.length()>95?plain.substring(0,92)+"...":plain;
    }

    private static void line(PDPageContentStream c,PDType1Font f,int size,
            float x,float y,String value) throws IOException {
        c.beginText();c.setFont(f,size);c.newLineAtOffset(x,y);
        c.showText(safe(value));c.endText();
    }

    private static String maskCpf(String cpf) {
        if(cpf==null||!cpf.matches("[0-9]{11}"))return "Nao informado";
        return "***.***.***-"+cpf.substring(9);
    }

    private static String formatCnpj(String cnpj) {
        if(cnpj==null||!cnpj.matches("[0-9]{14}"))return "Nao informado";
        return cnpj.substring(0,2)+"."+cnpj.substring(2,5)+"."+cnpj.substring(5,8)+"/"
                +cnpj.substring(8,12)+"-"+cnpj.substring(12);
    }
}
