package br.com.lcmtech.optics.receipts;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Link portador de token aleatório, temporário e revogável para WhatsApp. */
@RestController
@RequestMapping("/api/v1/public/receipts")
public class PublicReceiptController {
    private final JdbcTemplate jdbc;
    public PublicReceiptController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{40,64}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT r.receipt_number,r.pdf_bytes
            FROM receipt_share_tokens t
            JOIN sale_receipts r ON r.optic_id=t.optic_id AND r.id=t.receipt_id
            WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>now()
            """,ReceiptController.digest(token));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ReceiptController.document((byte[])rows.get(0).get("pdf_bytes"),
                "recibo-"+rows.get(0).get("receipt_number")+".pdf");
    }
}
