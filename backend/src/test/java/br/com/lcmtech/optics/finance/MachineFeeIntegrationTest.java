package br.com.lcmtech.optics.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Mesmos cálculos no preview e na venda; taxas conforme PhysioEvo V21/V22. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MachineFeeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private record Account(UUID optic,String email) {}
    private Account account(String role) {
        UUID optic=UUID.randomUUID(),person=UUID.randomUUID();
        String email=person+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'test','Vendedor',now())",person,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                optic,optic.toString(),"Ótica de teste",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)",
                optic,person,role);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        return new Account(optic,email);
    }

    @Test
    void cadastraPrazosEParceladoEPermiteEdicaoSemPerderVendas() throws Exception {
        Account shop=account("MASTER");
        String body="""
            {"name":"Stone","debitFee":1.99,"creditFee":2.5,
              "debitDays":1,"creditDays":30,"fixedFee":0.0,"anticipated":false,
              "creditTerms":[{"days":0,"fee":5.0},{"days":30,"fee":2.5}],
              "installmentFees":[
                {"installments":2,"saleFee":4.0,"installmentFee":0.5},
                {"installments":3,"saleFee":4.0,"installmentFee":1.5}]}
            """;
        var res=mvc.perform(post("/api/v1/machines").with(user(shop.email())).with(csrf())
                 .contentType(MediaType.APPLICATION_JSON).content(body))
                 .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode machine=mapper.readTree(res);
        String id=machine.path("id").asText();
        assertThat(machine.path("installmentFees").get(1).path("fee").decimalValue())
                .isEqualByComparingTo("5.50");

        String preview="""
            {"amount":1000,"paymentMethod":"CREDITO","installments":1,
            "cardMachineId":"%s","passFee":false,"settlementDays":0}
            """.formatted(id);
        JsonNode p=mapper.readTree(mvc.perform(post("/api/v1/machines/preview")
                .with(user(shop.email())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(preview)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(p.path("netAmount").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(p.path("receivables").size()).isEqualTo(1);

        String parcelPreview=preview.replace("\"installments\":1","\"installments\":3")
                .replace(",\"settlementDays\":0","");
        JsonNode three=mapper.readTree(mvc.perform(post("/api/v1/machines/preview")
                .with(user(shop.email())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(parcelPreview)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(three.path("feePercent").decimalValue()).isEqualByComparingTo("5.50");
        assertThat(three.path("netAmount").decimalValue()).isEqualByComparingTo("945.00");
        assertThat(three.path("receivables").size()).isEqualTo(3);

        String changed=body.replace("\"name\":\"Stone\"",
                "\"name\":\"Stone (taxa atualizada)\"");
        mvc.perform(put("/api/v1/machines/"+id).with(user(shop.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/machines/"+id).with(user(shop.email())).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/machines/preview").with(user(shop.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(preview))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vendedorVeMaquininhaParaCriarVendaMasNaoAlteraTaxas() throws Exception {
        Account master=account("MASTER"),seller=account("SELLER");
        UUID sellerId=jdbc.queryForObject("SELECT id FROM accounts WHERE email=?",
                UUID.class,seller.email());
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) "
                +"VALUES(?,?,'SELLER')",master.optic(),sellerId);
        jdbc.update("UPDATE optic_memberships SET active=false WHERE optic_id=? AND account_id=?",
                seller.optic(),sellerId);
        mvc.perform(get("/api/v1/machines").with(user(seller.email())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/machines").with(user(seller.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sem permissão\",\"debitFee\":1,\"creditFee\":2}"))
                .andExpect(status().isForbidden());
    }
}
