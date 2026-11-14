package br.com.lcmtech.optics.receipts;

import br.com.lcmtech.optics.security.OpticContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Emite comprovante apenas de parcelas/pagamentos de vendas efetivamente liquidados. */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {
    private final JdbcTemplate jdbc;
    private final OpticContext access;
    private final ObjectMapper json;
    private final SecureRandom random=new SecureRandom();

    @Value("${optics.public-base-url:http://localhost:5173}") private String baseUrl;

    public ReceiptController(JdbcTemplate jdbc,OpticContext access,ObjectMapper json) {
        this.jdbc=jdbc;this.access=access;this.json=json;
    }

    public record Branding(String legalName,String taxDocument,String address,
                           String contactPhone,String signerName,String signerRole) {}
    public record Asset(String kind,String mime,String dataBase64) {}

    @GetMapping("/settings")
    public Map<String,Object> settings(Authentication auth,HttpSession session) {
        access.requireMaster(auth,session);
        UUID optic=access.optic(auth,session);
        return jdbc.queryForMap("""
                SELECT o.trade_name,o.legal_name,o.tax_document,o.address,o.contact_phone,
                    r.signer_name,r.signer_role,
                    (r.logo IS NOT NULL) AS has_logo,
                    (r.signature IS NOT NULL) AS has_signature
                FROM optics o LEFT JOIN optic_receipt_identity r ON r.optic_id=o.id
                WHERE o.id=?
                """,optic);
    }

    @PutMapping("/settings")
    @Transactional
    public Map<String,Boolean> saveSettings(@RequestBody Branding req,
            Authentication auth,HttpSession session) {
        access.requireMaster(auth,session);
        if(req==null||req.legalName()==null||req.legalName().isBlank()||
                req.legalName().length()>180||
                req.address()==null||req.address().isBlank()||
                req.contactPhone()==null||req.contactPhone().isBlank())
            throw new IllegalArgumentException("Informe razão social, CNPJ, endereço e contato.");
        String cnpj=digits(req.taxDocument());
        if(!validCnpj(cnpj))
            throw new IllegalArgumentException("CNPJ inválido. Confira os dígitos verificadores.");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        jdbc.update("UPDATE optics SET legal_name=?,tax_document=?,address=?,contact_phone=?,"
                +"updated_at=now() WHERE id=?",req.legalName().trim(),cnpj,req.address().trim(),
                req.contactPhone().trim(),optic);
        jdbc.update("""
                INSERT INTO optic_receipt_identity(optic_id,signer_name,signer_role,updated_by)
                VALUES(?,?,?,?)
                ON CONFLICT(optic_id) DO UPDATE SET
                    signer_name=excluded.signer_name,signer_role=excluded.signer_role,
                    updated_by=excluded.updated_by,updated_at=now()
                """,optic,req.signerName(),req.signerRole(),actor);
        audit(optic,actor,"RECEIPT_IDENTITY_UPDATED",null);
        return Map.of("saved",true);
    }

    @PostMapping("/settings/asset")
    @Transactional
    public Map<String,Boolean> upload(@RequestBody Asset input,
            Authentication auth,HttpSession session) throws IOException {
        access.requireMaster(auth,session);
        if(input==null||!List.of("LOGO","SIGNATURE").contains(input.kind())||
                !List.of("image/png","image/jpeg").contains(input.mime())||
                input.dataBase64()==null||input.dataBase64().length()>2_800_000)
            throw new IllegalArgumentException("Envie PNG ou JPG de até 2 MB.");
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(input.dataBase64());}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Imagem inválida.");}
        if(bytes.length==0||bytes.length>2*1024*1024)
            throw new IllegalArgumentException("A imagem deve ter até 2 MB.");
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
        if(image==null||image.getWidth()>4096||image.getHeight()>4096)
            throw new IllegalArgumentException("A imagem enviada não é válida.");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        jdbc.update("INSERT INTO optic_receipt_identity(optic_id,updated_by) VALUES(?,?) "
                +"ON CONFLICT(optic_id) DO NOTHING",optic,actor);
        if("LOGO".equals(input.kind()))jdbc.update("""
            UPDATE optic_receipt_identity SET logo=?,logo_mime=?,updated_by=?,updated_at=now()
            WHERE optic_id=?
            """,bytes,input.mime(),actor,optic);
        else jdbc.update("""
            UPDATE optic_receipt_identity SET signature=?,signature_mime=?,
              updated_by=?,updated_at=now() WHERE optic_id=?
            """,bytes,input.mime(),actor,optic);
        audit(optic,actor,"RECEIPT_ASSET_UPDATED",null);
        return Map.of("saved",true);
    }

    @GetMapping("/sales/{saleId}/payments")
    public List<Map<String,Object>> payments(@PathVariable UUID saleId,
             Authentication auth,HttpSession session) {
        access.require(auth,session,"RECEIPTS_READ");
        UUID optic=access.optic(auth,session);
        br.com.lcmtech.optics.security.Owned.require(jdbc,"sales",optic,saleId,"Venda não encontrada.");
        // Uma linha por recebimento (baixa); parcela ainda sem recebimento aparece sozinha, sem recibo possível.
        return jdbc.queryForList("""
            SELECT e.id AS payment_id,st.id AS settlement_id,e.gross_amount,e.net_amount,e.fee_amount,
                e.due_on,st.paid_on AS settled_on,st.amount AS paid_amount,(st.reversed_at IS NOT NULL) AS settlement_reversed,
                e.net_amount-(SELECT coalesce(sum(x.amount),0) FROM finance_settlements x WHERE x.optic_id=e.optic_id
                    AND x.entry_id=e.id AND x.reversed_at IS NULL) AS open_amount,
                s.status,r.id AS receipt_id,r.receipt_number
            FROM finance_entries e
            JOIN sales s ON s.optic_id=e.optic_id AND s.id=e.sale_id
            LEFT JOIN finance_settlements st ON st.optic_id=e.optic_id AND st.entry_id=e.id
            LEFT JOIN sale_receipts r ON r.optic_id=st.optic_id AND r.settlement_id=st.id
            WHERE e.optic_id=? AND e.sale_id=? AND e.origin='SALE'
            ORDER BY e.due_on,e.created_at,st.paid_on,st.created_at
            """,optic,saleId);
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,HttpSession session) {
        access.require(auth,session,"RECEIPTS_READ");
        UUID optic=access.optic(auth,session);
        return jdbc.queryForList("""
            SELECT r.id,r.receipt_number,r.sale_id,r.finance_entry_id,r.payment_gross,
                r.paid_on,r.issued_at,c.full_name AS customer_name,c.phone AS customer_phone
            FROM sale_receipts r JOIN sales s ON s.optic_id=r.optic_id AND s.id=r.sale_id
            JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
            WHERE r.optic_id=? ORDER BY r.issued_at DESC LIMIT 300
            """,optic);
    }

    /** Atalho: recibo do recebimento mais recente da parcela (sem recibo ainda, ou o já emitido). */
    @PostMapping("/payments/{paymentId}/issue")
    @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> issue(@PathVariable UUID paymentId,
            Authentication auth,HttpSession session) throws IOException {
        access.require(auth,session,"RECEIPTS_ISSUE");
        UUID optic=access.optic(auth,session);
        var latest=jdbc.queryForList("""
                SELECT st.id FROM finance_settlements st
                WHERE st.optic_id=? AND st.entry_id=? AND st.reversed_at IS NULL
                ORDER BY (SELECT count(*) FROM sale_receipts r WHERE r.optic_id=st.optic_id AND r.settlement_id=st.id),
                         st.paid_on DESC,st.created_at DESC LIMIT 1
                """,UUID.class,optic,paymentId);
        if(latest.isEmpty())
            throw new IllegalArgumentException("Recibo disponível apenas para um pagamento liquidado e não estornado.");
        return issueSettlement(latest.get(0),auth,session);
    }

    /** Recibo de um recebimento. Parcial: mostra o valor pago e o saldo em aberto, sem declarar quitação. */
    @PostMapping("/settlements/{settlementId}/issue")
    @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> issueSettlement(@PathVariable UUID settlementId,
            Authentication auth,HttpSession session) throws IOException {
        access.require(auth,session,"RECEIPTS_ISSUE");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        // Já emitido: devolve o mesmo recibo (mesmo que o recebimento tenha sido estornado depois).
        var previous=jdbc.queryForList("""
                SELECT id,receipt_number FROM sale_receipts
                WHERE optic_id=? AND settlement_id=?
                """,optic,settlementId);
        if(!previous.isEmpty())return Map.of("id",previous.get(0).get("id"),
                "receiptNumber",previous.get(0).get("receipt_number"),"alreadyIssued",true);
        var payments=jdbc.queryForList("""
                SELECT e.id,e.sale_id,st.id AS settlement_id,st.amount AS paid_amount,st.paid_on AS settled_on,
                    e.gross_amount,e.net_amount,e.fee_amount,s.sold_on,s.status,s.payment_method,s.warranty_months,
                    c.full_name AS customer_name,c.cpf AS customer_cpf,
                    l.brand AS lens_brand,f.brand AS frame_brand,
                    o.trade_name,o.legal_name,o.tax_document,o.address,o.contact_phone
                FROM finance_settlements st
                JOIN finance_entries e ON e.optic_id=st.optic_id AND e.id=st.entry_id
                JOIN sales s ON s.optic_id=e.optic_id AND s.id=e.sale_id
                JOIN customers c ON c.optic_id=s.optic_id AND c.id=s.customer_id
                JOIN catalog_items l ON l.optic_id=s.optic_id AND l.id=s.lens_id
                JOIN catalog_items f ON f.optic_id=s.optic_id AND f.id=s.frame_id
                JOIN optics o ON o.id=s.optic_id
                WHERE st.optic_id=? AND st.id=? AND st.reversed_at IS NULL AND e.origin='SALE'
                  AND e.direction='INCOME' AND e.reversed_at IS NULL AND s.status<>'CANCELLED'
                FOR UPDATE OF st
                """,optic,settlementId);
        if(payments.isEmpty())
            throw new IllegalArgumentException("Recibo disponível apenas para um pagamento liquidado e não estornado.");
        UUID paymentId=(UUID)payments.get(0).get("id");

        Map<String,Object> entry=payments.get(0);
        String cnpj=digits((String)entry.get("tax_document"));
        if(!validCnpj(cnpj)||entry.get("legal_name")==null||
                entry.get("address")==null||entry.get("contact_phone")==null)
            throw new IllegalArgumentException("Configure o CNPJ e a identificação da ótica antes de emitir recibos.");
        UUID receipt=UUID.randomUUID(),sale=(UUID)entry.get("sale_id");
        jdbc.update("INSERT INTO optic_receipt_identity(optic_id,updated_by) VALUES(?,?) "
                +"ON CONFLICT(optic_id) DO NOTHING",optic,actor);
        var branding=jdbc.queryForMap("""
            SELECT next_receipt_number,logo,signature,signer_name,signer_role
            FROM optic_receipt_identity WHERE optic_id=? FOR UPDATE
            """,optic);
        long number=((Number)branding.get("next_receipt_number")).longValue();
        jdbc.update("UPDATE optic_receipt_identity SET next_receipt_number=next_receipt_number+1 "
                +"WHERE optic_id=?",optic);
        // Saldo da venda ainda em aberto (valor comercial): parcelas sem taxa pelo que falta, de cartão pelo total.
        BigDecimal pending=jdbc.queryForObject("""
            SELECT coalesce(sum(CASE WHEN e.fee_amount=0 THEN e.net_amount-p.paid
                                     WHEN e.net_amount-p.paid>0 THEN e.gross_amount ELSE 0 END),0)
            FROM finance_entries e CROSS JOIN LATERAL (SELECT coalesce(sum(x.amount),0) AS paid
                FROM finance_settlements x WHERE x.optic_id=e.optic_id AND x.entry_id=e.id AND x.reversed_at IS NULL) p
            WHERE e.optic_id=? AND e.sale_id=? AND e.direction='INCOME' AND e.origin='SALE' AND e.reversed_at IS NULL
            """,BigDecimal.class,optic,sale);
        boolean card=((BigDecimal)entry.get("fee_amount")).signum()>0;
        BigDecimal paidNet=(BigDecimal)entry.get("paid_amount");
        BigDecimal paidGross=card?(BigDecimal)entry.get("gross_amount"):paidNet;
        BigDecimal paidFee=card?(BigDecimal)entry.get("fee_amount"):BigDecimal.ZERO;
        var paid=((java.sql.Date)entry.get("settled_on")).toLocalDate();
        var sold=((java.sql.Date)entry.get("sold_on")).toLocalDate();
        var data=new ReceiptPdfRenderer.Details(receipt,number,
                (String)entry.get("trade_name"),(String)entry.get("legal_name"),cnpj,
                (String)entry.get("address"),(String)entry.get("contact_phone"),
                (String)entry.get("customer_name"),(String)entry.get("customer_cpf"),
                (String)entry.get("lens_brand"),(String)entry.get("frame_brand"),
                (String)entry.get("payment_method"),sold,paid,
                paidGross,paidFee,paidNet,pending,
                (String)branding.get("signer_name"),(String)branding.get("signer_role"),
                (byte[])branding.get("logo"),(byte[])branding.get("signature"),
                entry.get("warranty_months")!=null&&((Number)entry.get("warranty_months")).intValue()>0
                    ?sold.plusMonths(((Number)entry.get("warranty_months")).intValue()):null);
        byte[] pdf=ReceiptPdfRenderer.render(data);
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("receiptNumber",number);
        snapshot.put("receiptId",receipt.toString());
        snapshot.put("saleId",sale.toString());
        snapshot.put("paidOn",paid.toString());
        snapshot.put("shop",data.shopName());
        snapshot.put("cnpj",cnpj);
        snapshot.put("customer",data.customerName());
        snapshot.put("paymentGross",data.paymentGross());
        snapshot.put("settlementId",settlementId.toString());
        snapshot.put("pendingGross",data.pending());
        snapshot.put("signedBy",data.signerName());
        jdbc.update("""
            INSERT INTO sale_receipts(optic_id,id,receipt_number,sale_id,finance_entry_id,settlement_id,
                paid_on,payment_gross,pdf_bytes,snapshot,issued_by)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?)
            """,optic,receipt,number,sale,paymentId,settlementId,java.sql.Date.valueOf(paid),
                data.paymentGross(),pdf,json.writeValueAsString(snapshot),actor);
        audit(optic,actor,"SALE_PAYMENT_RECEIPT_ISSUED",receipt);
        return Map.of("id",receipt,"receiptNumber",number,"alreadyIssued",false);
    }

    @GetMapping("/{receiptId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID receiptId,
            Authentication auth,HttpSession session) {
        access.require(auth,session,"RECEIPTS_READ");
        UUID optic=access.optic(auth,session);
        var saved=jdbc.queryForList("SELECT receipt_number,pdf_bytes FROM sale_receipts "
                +"WHERE optic_id=? AND id=?",optic,receiptId);
        if(saved.isEmpty())throw new java.util.NoSuchElementException("Recibo não encontrado.");
        return document((byte[])saved.get(0).get("pdf_bytes"),
                "recibo-"+saved.get(0).get("receipt_number")+".pdf");
    }

    @PostMapping("/{receiptId}/share")
    @Transactional
    public Map<String,Object> share(@PathVariable UUID receiptId,
            Authentication auth,HttpSession session) {
        access.require(auth,session,"RECEIPTS_READ");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM sale_receipts "
                +"WHERE optic_id=? AND id=?",Integer.class,optic,receiptId);
        if(count==null||count==0)throw new java.util.NoSuchElementException("Recibo não encontrado.");
        byte[] raw=new byte[32];random.nextBytes(raw);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID shareId=UUID.randomUUID();
        Instant expiration=Instant.now().plusSeconds(24*3600);
        jdbc.update("""
            INSERT INTO receipt_share_tokens
                (id,optic_id,receipt_id,token_hash,expires_at,created_by)
            VALUES(?,?,?,?,?,?)
            """,shareId,optic,receiptId,digest(token),
            java.sql.Timestamp.from(expiration),actor);
        audit(optic,actor,"RECEIPT_SHARE_LINK_CREATED",receiptId);
        return Map.of("url",baseUrl.replaceAll("/+$","")+"/api/v1/public/receipts/"+token,
                "shareId",shareId,"validUntil",expiration.toString());
    }

    @DeleteMapping("/shares/{shareId}")
    @Transactional
    public Map<String,Boolean> revoke(@PathVariable UUID shareId,
            Authentication auth,HttpSession session) {
        access.require(auth,session,"RECEIPTS_ISSUE");
        UUID optic=access.optic(auth,session);
        br.com.lcmtech.optics.security.Owned.require(jdbc,"receipt_share_tokens",optic,shareId,"Link não encontrado.");
        int n=jdbc.update("""
            UPDATE receipt_share_tokens SET revoked_at=now()
            WHERE optic_id=? AND id=? AND revoked_at IS NULL
            """,optic,shareId);
        return Map.of("revoked",n>0);
    }

    static ResponseEntity<byte[]> document(byte[] content,String filename) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\""+filename+"\"")
                .header("Content-Type","application/pdf").body(content);
    }

    static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private static String digits(String value) {
        return value==null?"":value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]","");
    }

    /** CNPJ brasileiro tradicional, 14 dígitos e verificadores mod 11. */
    static boolean validCnpj(String cnpj) {
        if(cnpj==null||!cnpj.matches("[A-Z0-9]{12}[0-9]{2}")||
                cnpj.chars().distinct().count()==1)
            return false;
        int[] weight1={5,4,3,2,9,8,7,6,5,4,3,2};
        int[] weight2={6,5,4,3,2,9,8,7,6,5,4,3,2};
        int sum=0;
        for(int i=0;i<12;i++)sum+=(cnpj.charAt(i)-48)*weight1[i];
        int digit=sum%11<2?0:11-sum%11;
        if(digit!=cnpj.charAt(12)-'0')return false;
        sum=0;for(int i=0;i<13;i++)sum+=(cnpj.charAt(i)-48)*weight2[i];
        return (sum%11<2?0:11-sum%11)==cnpj.charAt(13)-'0';
    }

    private void audit(UUID optic,UUID actor,String kind,UUID id) {
        jdbc.update("""
            INSERT INTO audit_events(id,optic_id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,? ,?,'RECEIPT',?)
            """,UUID.randomUUID(),optic,actor,kind,id);
    }
}
