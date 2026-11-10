package br.com.lcmtech.optics.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReceiptIssueIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Fixture(UUID optic,String email,UUID sale,UUID payment){}

    private Fixture seed(boolean paid) {
        UUID optic=UUID.randomUUID(),account=UUID.randomUUID();
        UUID customer=UUID.randomUUID(),lens=UUID.randomUUID(),frame=UUID.randomUUID();
        UUID sale=UUID.randomUUID(),payment=UUID.randomUUID();
        String email=account+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'hash','Master',now())",account,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,legal_name,tax_document,"
                +"address,contact_phone) VALUES(?,?,?,?,'Ótica Exemplo LTDA','11222333000181',"
                +"'Rua de Teste, 100','98999999999')",optic,optic.toString(),"Ótica Exemplo",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')",
                optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,phone) "
                +"VALUES(?,?,'Cliente Teste','12345678901','98999990000')",optic,customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Cristal')",
                optic,lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Clássica')",
                optic,frame);
        jdbc.update("""
            INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,
                replacement_months,gross_amount,fee_amount,charged_amount,net_amount,payment_method)
            VALUES(?,?,?,?,?,?,12,300,0,300,300,'PIX')
            """,optic,sale,customer,account,lens,frame);
        jdbc.update("""
            INSERT INTO finance_entries(optic_id,id,direction,category,description,
                gross_amount,net_amount,occurred_on,due_on,settled_on,origin,sale_id,created_by)
            VALUES(?,?,'INCOME','Venda','Pagamento',300,300,current_date,current_date,?,
                'SALE',?,?)
            """,optic,payment,paid?Date.valueOf("2026-11-09"):null,sale,account);
        return new Fixture(optic,email,sale,payment);
    }

    @Test
    void apenasPagamentoLiquidadoPodeEmitirReciboIdempotenteECompartilhar() throws Exception {
        Fixture shop=seed(true);
        var first=mvc.perform(post("/api/v1/receipts/payments/"+shop.payment()+"/issue")
                .with(user(shop.email())).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created=json.readTree(first);
        assertThat(created.path("alreadyIssued").asBoolean()).isFalse();
        UUID receipt=UUID.fromString(created.path("id").asText());
        var second=mvc.perform(post("/api/v1/receipts/payments/"+shop.payment()+"/issue")
                .with(user(shop.email())).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second).path("id").asText()).isEqualTo(receipt.toString());
        var pdf=mvc.perform(get("/api/v1/receipts/"+receipt+"/download")
                .with(user(shop.email()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).startsWith(new byte[]{37,80,68,70});

        var shareResponse=mvc.perform(post("/api/v1/receipts/"+receipt+"/share")
                .with(user(shop.email())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode share=json.readTree(shareResponse);
        String url=share.path("url").asText();
        String token=url.substring(url.lastIndexOf('/')+1);
        mvc.perform(get("/api/v1/public/receipts/"+token))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/receipts/shares/"+share.path("shareId").asText())
                .with(user(shop.email())).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/receipts/"+token))
                .andExpect(status().isNotFound());
    }

    @Test
    void naoEmiteParaRecebivelAindaPendenteNemOutroTenant() throws Exception {
        Fixture pending=seed(false),other=seed(true);
        mvc.perform(post("/api/v1/receipts/payments/"+pending.payment()+"/issue")
                .with(user(pending.email())).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/receipts/payments/"+other.payment()+"/issue")
                .with(user(pending.email())).with(csrf())).andExpect(status().isBadRequest());
    }
}
