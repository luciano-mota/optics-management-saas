package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DetailedReportIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic, String master, String seller, UUID clinicA, UUID clinicB, UUID receiptPayment,
                        UUID reversedPayment) {}

    private Shop seed(String name) throws Exception {
        UUID optic = UUID.randomUUID(), master = UUID.randomUUID(), seller = UUID.randomUUID();
        String masterEmail = master + "@example.test", sellerEmail = seller + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash',?,now())",
                master, masterEmail, "Master " + name);
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash',?,now())",
                seller, sellerEmail, "Vendedor " + name);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,legal_name,tax_document,address,contact_phone) "
                + "VALUES(?,?,?,?,'Ótica LTDA','11222333000181','Rua de Teste, 100','98999999999')",
                optic, optic.toString(), name, masterEmail);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, master);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'SELLER')", optic, seller);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);

        UUID clinicA = UUID.randomUUID(), clinicB = UUID.randomUUID();
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,?)", optic, clinicA, "Clínica Visão " + name);
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,?)", optic, clinicB, "Clínica Olhar " + name);
        UUID converted = UUID.randomUUID(), absent = UUID.randomUUID();
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,cpf,created_by) VALUES(?,?,?,?,?)",
                optic, converted, "Paciente Comprou " + name, "39053344705", master);
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,created_by) VALUES(?,?,?,?)",
                optic, absent, "Paciente Faltou " + name, master);
        jdbc.update("INSERT INTO optical_appointments(optic_id,id,patient_id,clinic_id,starts_at,ends_at,status,created_by) "
                + "VALUES(?,?,?,?,now()-interval '2 days',now()-interval '2 days'+interval '30 minutes','COMPLETED',?)",
                optic, UUID.randomUUID(), converted, clinicA, master);
        jdbc.update("INSERT INTO optical_appointments(optic_id,id,patient_id,clinic_id,starts_at,ends_at,status,created_by) "
                + "VALUES(?,?,?,?,now()-interval '1 day',now()-interval '1 day'+interval '30 minutes','NO_SHOW',?)",
                optic, UUID.randomUUID(), absent, clinicB, master);

        UUID customer = UUID.randomUUID(), lens = UUID.randomUUID(), frame = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,source_patient_id) VALUES(?,?,?,?,?)",
                optic, customer, "Paciente Comprou " + name, "39053344705", converted);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Cristal')", optic, lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Clássica')", optic, frame);
        UUID sale = UUID.randomUUID(), cancelled = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,
                    fee_amount,charged_amount,net_amount,payment_method,sold_on)
                VALUES(?,?,?,?,?,?,12,300,9,300,291,'CREDITO',current_date)
                """, optic, sale, customer, seller, lens, frame);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,
                    fee_amount,charged_amount,net_amount,payment_method,sold_on)
                VALUES(?,?,?,?,?,?,12,150,0,150,150,'PIX',current_date)
                """, optic, cancelled, customer, master, lens, frame);
        UUID payment = UUID.randomUUID(), reversed = UUID.randomUUID();
        for (UUID[] entry : new UUID[][]{{payment, sale}, {reversed, cancelled}})
            jdbc.update("""
                    INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,net_amount,
                        occurred_on,due_on,settled_on,origin,sale_id,created_by)
                    VALUES(?,?,'INCOME','Venda','Pagamento',100,100,current_date,current_date,current_date,'SALE',?,?)
                    """, optic, entry[0], entry[1], master);
        Shop shop = new Shop(optic, masterEmail, sellerEmail, clinicA, clinicB, payment, reversed);
        for (UUID p : List.of(payment, reversed))
            mvc.perform(post("/api/v1/receipts/payments/" + p + "/issue").with(user(masterEmail)).with(csrf()))
                    .andExpect(status().isOk());
        // Venda cancelada depois do recibo: o pagamento é estornado e o recibo passa a constar como estornado.
        jdbc.update("UPDATE sales SET status='CANCELLED' WHERE optic_id=? AND id=?", optic, cancelled);
        jdbc.update("UPDATE finance_entries SET reversed_at=now() WHERE optic_id=? AND id=?", optic, reversed);
        return shop;
    }

    private JsonNode report(String who, String kind, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reports/detailed/" + kind + query).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> column(JsonNode report, String header) {
        int index = -1;
        for (int i = 0; i < report.path("headers").size(); i++)
            if (report.path("headers").get(i).asText().equals(header)) index = i;
        assertThat(index).as(header).isGreaterThanOrEqualTo(0);
        List<String> values = new ArrayList<>();
        for (JsonNode row : report.path("rows")) values.add(row.get(index).asText());
        return values;
    }

    @Test
    void consultasPorClinicaSituacaoEConversaoSemContarPacienteComoVenda() throws Exception {
        Shop shop = seed("Ótica Alfa");
        JsonNode all = report(shop.master(), "consultas", "");
        assertThat(all.path("totalRows").asInt()).isEqualTo(2);
        assertThat(column(all, "Situação")).containsExactly("Não compareceu", "Consulta realizada");
        assertThat(column(all, "Convertida em venda")).containsExactly("Não", "Sim");
        assertThat(all.path("summary").get(1).asText()).contains("Convertidas em venda: 1").contains("100,0%");
        assertThat(all.path("groups")).hasSize(2);

        JsonNode clinic = report(shop.master(), "consultas", "?clinicId=" + shop.clinicB());
        assertThat(column(clinic, "Clínica")).containsExactly("Clínica Olhar Ótica Alfa");
        JsonNode completed = report(shop.master(), "consultas", "?status=COMPLETED");
        assertThat(completed.path("totalRows").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/reports/detailed/consultas?status=PAGO").with(user(shop.master())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/detailed/consultas?from=2026-11-10&to=2026-11-01").with(user(shop.master())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recibosEmitidosEEstornados() throws Exception {
        Shop shop = seed("Ótica Beta");
        JsonNode all = report(shop.master(), "recibos", "");
        assertThat(all.path("totalRows").asInt()).isEqualTo(2);
        assertThat(column(all, "Situação")).containsExactlyInAnyOrder("Válido", "Estornado");
        assertThat(all.path("summary").get(0).asText()).contains("Válidos: 1").contains("Estornados: 1");
        JsonNode reversed = report(shop.master(), "recibos", "?status=REVERSED");
        assertThat(column(reversed, "Situação")).containsExactly("Estornado");
        assertThat(column(reversed, "Estornado em").get(0)).isNotBlank();
    }

    @Test
    void vendasEscondemTaxasELiquidoDeQuemNaoVeOFinanceiro() throws Exception {
        Shop shop = seed("Ótica Gama");
        JsonNode master = report(shop.master(), "vendas", "");
        assertThat(master.path("headers").toString()).contains("Taxas").contains("Líquido");
        assertThat(column(master, "Origem")).containsOnly("Paciente de consulta");
        assertThat(master.path("summary").get(0).asText()).contains("Vendas válidas: 1").contains("Canceladas: 1");
        JsonNode seller = report(shop.seller(), "vendas", "");
        assertThat(seller.path("headers").toString()).doesNotContain("Taxas").doesNotContain("Líquido");
        assertThat(seller.path("summary")).hasSize(1);
        JsonNode cancelled = report(shop.master(), "vendas", "?status=CANCELLED");
        assertThat(column(cancelled, "Situação")).containsExactly("Cancelado");
    }

    @Test
    void pacientesSemDadosDeContatoECompraSoComVendaValida() throws Exception {
        Shop shop = seed("Ótica Delta");
        JsonNode all = report(shop.master(), "pacientes", "");
        assertThat(all.path("headers").toString()).doesNotContain("CPF").doesNotContain("Telefone");
        assertThat(column(all, "Comprou na ótica")).containsExactly("Sim", "Não");
        JsonNode clinic = report(shop.master(), "pacientes", "?clinicId=" + shop.clinicB());
        assertThat(column(clinic, "Paciente")).containsExactly("Paciente Faltou Ótica Delta");
    }

    @Test
    void exportaOsTresFormatosComOMesmoConteudo() throws Exception {
        Shop shop = seed("Ótica Épsilon");
        for (String kind : List.of("vendas", "consultas", "recibos", "pacientes")) {
            byte[] csv = mvc.perform(get("/api/v1/reports/detailed/" + kind + "/export/csv").with(user(shop.master())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            String text = new String(csv, StandardCharsets.UTF_8);
            assertThat(text).startsWith("﻿\"");
            assertThat(text.lines().count()).as(kind).isGreaterThanOrEqualTo(3);
            byte[] xlsx = mvc.perform(get("/api/v1/reports/detailed/" + kind + "/export/xlsx").with(user(shop.master())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(xlsx).startsWith(new byte[]{'P', 'K'});
            byte[] pdf = mvc.perform(get("/api/v1/reports/detailed/" + kind + "/export/pdf").with(user(shop.master())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(pdf).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        }
        mvc.perform(get("/api/v1/reports/detailed/vendas/export/doc").with(user(shop.master())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/detailed/estoque").with(user(shop.master())))
                .andExpect(status().isNotFound());
        // Atalho antigo continua funcionando.
        mvc.perform(get("/api/v1/reports/sales.csv").with(user(shop.master()))).andExpect(status().isOk());
    }

    @Test
    void cadaOticaSoEnxergaOsProprios() throws Exception {
        Shop alfa = seed("Ótica Um"), beta = seed("Ótica Dois");
        for (String kind : List.of("vendas", "consultas", "recibos", "pacientes")) {
            JsonNode mine = report(beta.master(), kind, "");
            assertThat(mine.path("rows").toString()).as(kind).doesNotContain("Ótica Um");
            assertThat(mine.path("totalRows").asInt()).as(kind).isEqualTo(2);
        }
        // Filtrar pela clínica de outra ótica não traz nada.
        JsonNode foreign = report(beta.master(), "consultas", "?clinicId=" + alfa.clinicA());
        assertThat(foreign.path("totalRows").asInt()).isZero();
        String csv = mvc.perform(get("/api/v1/reports/detailed/consultas/export/csv?clinicId=" + alfa.clinicA())
                        .with(user(beta.master()))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("Ótica Um");
    }

    @Test
    void vendedorNaoVeConsultasNemQuemNaoTemRelatorios() throws Exception {
        Shop shop = seed("Ótica Zeta");
        // O perfil vendedor não tem APPOINTMENTS_READ.
        mvc.perform(get("/api/v1/reports/detailed/consultas").with(user(shop.seller()))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO member_permission_overrides(optic_id,account_id,permission,effect) "
                + "SELECT optic_id,account_id,'REPORTS_READ','DENY' FROM optic_memberships m JOIN accounts a "
                + "ON a.id=m.account_id WHERE a.email=?", shop.seller());
        mvc.perform(get("/api/v1/reports/detailed/vendas").with(user(shop.seller()))).andExpect(status().isForbidden());
    }

    @Test
    void csvNeutralizaFormulas() {
        assertThat(TabularReport.escapeCsv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(TabularReport.escapeCsv("Ana")).isEqualTo("\"Ana\"");
    }
}
