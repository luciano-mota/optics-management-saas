package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
class SalesFilterIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic, String master, UUID ana, UUID bia) {}

    private UUID account(UUID optic, String name, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash',?,now())",
                id, id + "@example.test", name);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, id, role);
        return id;
    }

    private UUID item(UUID optic, String kind, String brand, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand,category) VALUES(?,?,?,?,?)", optic, id, kind, brand, category);
        return id;
    }

    private UUID sale(UUID optic, UUID customer, UUID seller, UUID lens, UUID frame, String method, String amount, String status) {
        UUID id = UUID.randomUUID();
        BigDecimal v = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,fee_amount,
                    charged_amount,net_amount,payment_method,sold_on,status)
                VALUES(?,?,?,?,?,?,12,?,0,?,?,?,'2026-11-10',?)
                """, optic, id, customer, seller, lens, frame, v, v, v, method, status);
        return id;
    }

    private Shop seed(String prefix) {
        UUID optic = UUID.randomUUID();
        UUID master = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                master, master + "@example.test");
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)", optic, optic.toString(),
                master + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, master);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        UUID ana = account(optic, prefix + " Ana", "SELLER"), bia = account(optic, prefix + " Bia", "SELLER");
        UUID essilor = item(optic, "LENS", prefix + "Essilor", "MULTIFOCAL"), zeiss = item(optic, "LENS", prefix + "Zeiss", "MONOFOCAL");
        UUID rayban = item(optic, "FRAME", prefix + "Ray-Ban", "SOLAR"), oakley = item(optic, "FRAME", prefix + "Oakley", "GRAU");
        UUID old = item(optic, "FRAME", prefix + "Antiga", null);
        UUID patient = UUID.randomUUID();
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,created_by) VALUES(?,?,'João',?)", optic, patient, master);
        UUID maria = UUID.randomUUID(), joao = UUID.randomUUID(), rosa = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf) VALUES(?,?,'Maria Souza','39053344705')", optic, maria);
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,source_patient_id) VALUES(?,?,'João Paciente',?)", optic, joao, patient);
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Rosa Visitante')", optic, rosa);
        sale(optic, maria, ana, essilor, rayban, "PIX", "500.00", "DELIVERED");
        sale(optic, joao, bia, zeiss, oakley, "CREDITO", "900.00", "AWAITING_PRODUCTION");
        UUID visitSale = sale(optic, rosa, ana, zeiss, old, "DEBITO", "300.00", "AWAITING_PRODUCTION");
        jdbc.update("""
                INSERT INTO home_visits(optic_id,id,lead_name,lead_phone,lead_address,appointment_at,consent_at,status,
                    customer_id,sale_id) VALUES(?,?,'Rosa','98999990000','Rua X',now(),now(),'CONVERTED',?,?)
                """, optic, UUID.randomUUID(), rosa, visitSale);
        sale(optic, maria, bia, essilor, oakley, "PIX", "200.00", "CANCELLED");
        return new Shop(optic, master + "@example.test", ana, bia);
    }

    private JsonNode query(String who, String q) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reports/detailed/vendas?" + q).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> column(JsonNode report, String header) {
        int index = -1;
        for (int i = 0; i < report.path("headers").size(); i++)
            if (report.path("headers").get(i).asText().equals(header)) index = i;
        List<String> values = new ArrayList<>();
        for (JsonNode row : report.path("rows")) values.add(row.get(index).asText());
        return values;
    }

    @Test
    void filtraPorMarcaTipoVendedoraPagamentoOrigemECliente() throws Exception {
        Shop s = seed("A");
        assertThat(query(s.master(), "").path("totalRows").asInt()).isEqualTo(4);
        assertThat(column(query(s.master(), "frameBrand=a" + "ray-ban"), "Armação")).containsExactly("ARay-Ban · Solar");
        assertThat(column(query(s.master(), "lensCategory=MULTIFOCAL"), "Lente")).containsOnly("AEssilor · Multifocal").hasSize(2);
        assertThat(column(query(s.master(), "frameCategory=NONE"), "Armação")).containsExactly("AAntiga");
        assertThat(column(query(s.master(), "sellerId=" + s.bia() + "&paymentMethod=CREDITO"), "Cliente"))
                .containsExactly("João Paciente");
        assertThat(column(query(s.master(), "origin=VISITA"), "Origem")).containsExactly("Visita em domicílio");
        assertThat(column(query(s.master(), "origin=CONSULTA"), "Cliente")).containsExactly("João Paciente");
        assertThat(column(query(s.master(), "origin=DIRETA"), "Cliente")).containsOnly("Maria Souza").hasSize(2);
        assertThat(query(s.master(), "customer=maria").path("totalRows").asInt()).isEqualTo(2);
        assertThat(query(s.master(), "customer=390.533").path("totalRows").asInt()).isEqualTo(2);
        assertThat(column(query(s.master(), "lensBrand=AZeiss&sellerId=" + s.ana()), "Cliente"))
                .containsExactly("Rosa Visitante");
        JsonNode all = query(s.master(), "");
        assertThat(all.path("summary").get(1).asText()).contains("Ticket médio: R$ 566,67");
        JsonNode filtered = query(s.master(), "frameBrand=ARay-Ban&paymentMethod=PIX");
        assertThat(filtered.path("summary").toString()).contains("Filtros: armação ARay-Ban, pagamento Pix");
    }

    @Test
    void recusaValoresForaDaListaEExportaComOsMesmosFiltros() throws Exception {
        Shop s = seed("B");
        for (String bad : List.of("paymentMethod=BOLETO", "lensCategory=SOLAR", "frameCategory=MULTIFOCAL", "origin=LOJA",
                "status=PAGO", "from=2026-11-10&to=2026-11-01"))
            mvc.perform(get("/api/v1/reports/detailed/vendas?" + bad).with(user(s.master())))
                    .andExpect(status().isBadRequest());
        String csv = mvc.perform(get("/api/v1/reports/detailed/vendas/export/csv?frameBrand=BRay-Ban").with(user(s.master())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("BRay-Ban").doesNotContain("BOakley");
        for (String format : List.of("xlsx", "pdf"))
            assertThat(mvc.perform(get("/api/v1/reports/detailed/vendas/export/" + format + "?lensCategory=MONOFOCAL")
                    .with(user(s.master()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                    .hasSizeGreaterThan(200);
    }

    @Test
    void opcoesEFiltrosSoEnxergamAPropriaOtica() throws Exception {
        Shop a = seed("A"), b = seed("B");
        JsonNode options = json.readTree(mvc.perform(get("/api/v1/reports/detailed/vendas/options").with(user(b.master())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(options.path("frameBrands").toString()).contains("BRay-Ban").doesNotContain("ARay-Ban");
        assertThat(options.path("sellers").toString()).contains("B Ana").doesNotContain("A Ana");
        assertThat(options.path("lensCategories").path("MULTIFOCAL").asText()).isEqualTo("Multifocal (progressiva)");
        // Vendedora ou marca de outra ótica no filtro não trazem nada.
        JsonNode other = query(b.master(), "sellerId=" + a.ana());
        assertThat(other.path("totalRows").asInt()).isZero();
        // Nem o nome da vendedora de outra ótica aparece (rodapé de filtros da tela e das exportações).
        assertThat(other.toString()).doesNotContain("A Ana").contains("não encontrado(a) nesta ótica");
        String csv = mvc.perform(get("/api/v1/reports/detailed/vendas/export/csv?sellerId=" + a.ana()).with(user(b.master())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("A Ana");
        assertThat(query(b.master(), "sellerId=" + b.ana()).toString()).contains("vendedor(a) B Ana");
        assertThat(query(b.master(), "frameBrand=ARay-Ban").path("totalRows").asInt()).isZero();
    }
}
