package br.com.lcmtech.optics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Integração real contra PostgreSQL para impedir vazamento e lançamentos inconsistentes. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpticalShopFlowIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic,UUID account,String email,UUID lens,UUID frame,UUID customer){}

    private Shop bootstrap() {
        UUID shop=UUID.randomUUID(),account=UUID.randomUUID();
        UUID lens=UUID.randomUUID(),frame=UUID.randomUUID(),customer=UUID.randomUUID();
        String email=account.toString()+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'test-hash','Tester',now())",account,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                shop,shop.toString(),"Ótica de Teste",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')",
                shop,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",shop);
        jdbc.update("INSERT INTO site_settings(optic_id) VALUES(?)",shop);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Lente Teste')",shop,lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Armação Teste')",shop,frame);
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Cliente Teste')",shop,customer);
        return new Shop(shop,account,email,lens,frame,customer);
    }

    @Test void cadaOticaVeApenasSeuProprioCadastro() throws Exception {
        Shop first=bootstrap(),second=bootstrap();
        jdbc.update("UPDATE customers SET full_name='Cliente Exclusivo B' WHERE optic_id=?",
                second.optic());
        var a=mvc.perform(get("/api/v1/customers").with(user(first.email())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(a).doesNotContain("Cliente Exclusivo B");
        assertThat(a).contains("Cliente Teste");
        mvc.perform(get("/api/v1/platform/optics").with(user(first.email())))
                .andExpect(status().isForbidden());
    }

    @Test void vendeComClienteERegistraReceitaEDepoisEntrega() throws Exception {
        Shop a=bootstrap();
        String request=json.writeValueAsString(Map.of(
                "customerId",a.customer().toString(),"lensId",a.lens().toString(),
                "frameId",a.frame().toString(),"amount","350.00","paymentMethod","PIX",
                "installments",1,"replacementMonths",12,"idempotencyKey","flow-"+UUID.randomUUID()));
        var response=mvc.perform(post("/api/v1/sales").with(user(a.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_entries "
                +"WHERE optic_id=? AND sale_id=? AND settled_on IS NOT NULL",Integer.class,a.optic(),UUID.fromString(id)))
                .isEqualTo(1);
        mvc.perform(patch("/api/v1/sales/"+id+"/status").with(user(a.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READY_FOR_DELIVERY\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/sales/"+id+"/status").with(user(a.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE optic_id=? AND id=?",
                String.class,a.optic(),UUID.fromString(id))).isEqualTo("DELIVERED");
        mvc.perform(patch("/api/v1/sales/"+id+"/status").with(user(a.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void sitePublicoPrecisaDaLiberacaoDaPlataforma() throws Exception {
        Shop a=bootstrap();
        mvc.perform(get("/api/v1/public/site/"+a.optic()))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE optics SET site_enabled=true WHERE id=?",a.optic());
        mvc.perform(get("/api/v1/public/site/"+a.optic()))
                .andExpect(status().isOk());
    }

    @Test void soUsuarioAutorizadoAcessaAnexos() throws Exception {
        Shop a=bootstrap(),b=bootstrap();
        UUID file=UUID.randomUUID();
        jdbc.update("INSERT INTO file_attachments(optic_id,id,customer_id,original_name,content_type,bytes) "
                +"VALUES(?,?,?,'rx.pdf','application/pdf',?)",
                a.optic(),file,a.customer(),new byte[]{37,80,68,70});
        // Arquivo de outra ótica: 404, como qualquer registro que não é desta ótica (não revela que existe).
        mvc.perform(get("/api/v1/attachments/"+file+"/download").with(user(b.email())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/attachments/customers/"+a.customer()).with(user(b.email())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/attachments/"+file+"/download").with(user(a.email())))
                .andExpect(status().isOk());
    }
}
