package br.com.lcmtech.optics.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockAndServiceOrderIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic, String master, UUID customer, UUID lens, UUID frame) {}

    private Shop seed() {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica Estoque',?)", optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        UUID customer = UUID.randomUUID(), lens = UUID.randomUUID(), frame = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,phone) VALUES(?,?,'Cliente OS','98999990000')", optic, customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Essilor')", optic, lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand,model) VALUES(?,?,'FRAME','Ray-Ban','RB5154')", optic, frame);
        return new Shop(optic, email, customer, lens, frame);
    }

    private MvcResult call(String who, String method, String path, String body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            default -> get(path);
        };
        if (!"GET".equals(method)) req = req.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body);
        return mvc.perform(req.with(user(who))).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private UUID sale(Shop s, Integer warranty) throws Exception {
        String body = "{\"customerId\":\"" + s.customer() + "\",\"lensId\":\"" + s.lens() + "\",\"frameId\":\"" + s.frame()
                + "\",\"amount\":500,\"paymentMethod\":\"PIX\",\"installments\":1,\"passFee\":false,\"replacementMonths\":12"
                + (warranty == null ? "" : ",\"warrantyMonths\":" + warranty) + "}";
        MvcResult r = call(s.master(), "POST", "/api/v1/sales", body);
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(body(r).path("id").asText());
    }

    private int stockOf(Shop s, UUID item) throws Exception {
        for (JsonNode row : body(call(s.master(), "GET", "/api/v1/stock", null)))
            if (row.path("id").asText().equals(item.toString())) return row.path("quantity").asInt();
        return Integer.MIN_VALUE;
    }

    @Test
    void fornecedorCadastraEditaDesativaEValida() throws Exception {
        Shop s = seed(), other = seed();
        assertThat(status(call(s.master(), "POST", "/api/v1/suppliers", "{\"name\":\"Lab\",\"document\":\"123\"}"))).isEqualTo(400);
        UUID lab = UUID.fromString(body(call(s.master(), "POST", "/api/v1/suppliers",
                "{\"name\":\"Laboratório Visão\",\"kind\":\"LABORATORIO\",\"document\":\"11.222.333/0001-81\",\"phone\":\"(98) 3222-0000\"}"))
                .path("id").asText());
        assertThat(status(call(s.master(), "PUT", "/api/v1/suppliers/" + lab,
                "{\"name\":\"Laboratório Visão Ltda\",\"kind\":\"AMBOS\",\"document\":\"11222333000181\"}"))).isEqualTo(200);
        assertThat(status(call(other.master(), "PUT", "/api/v1/suppliers/" + lab, "{\"name\":\"Invasor\"}"))).isEqualTo(404);
        assertThat(status(call(other.master(), "PATCH", "/api/v1/suppliers/" + lab + "/active", "{\"active\":false}"))).isEqualTo(404);
        JsonNode list = body(call(s.master(), "GET", "/api/v1/suppliers", null));
        assertThat(list.get(0).path("name").asText()).isEqualTo("Laboratório Visão Ltda");
        assertThat(list.get(0).path("document").asText()).isEqualTo("11222333000181");
        assertThat(body(call(other.master(), "GET", "/api/v1/suppliers", null))).isEmpty();
        assertThat(status(call(s.master(), "PATCH", "/api/v1/suppliers/" + lab + "/active", "{\"active\":false}"))).isEqualTo(200);
    }

    @Test
    void estoqueEntraSaiNaVendaVoltaNoCancelamentoEAvisaQuandoBaixo() throws Exception {
        Shop s = seed();
        UUID supplier = UUID.fromString(body(call(s.master(), "POST", "/api/v1/suppliers", "{\"name\":\"Distribuidora\"}")).path("id").asText());
        assertThat(status(call(s.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + s.frame() + "\",\"kind\":\"ENTRADA\",\"quantity\":5}"))).isEqualTo(400); // ainda não controla
        assertThat(status(call(s.master(), "PATCH", "/api/v1/stock/items/" + s.frame(), "{\"trackStock\":true,\"minStock\":2}")))
                .isEqualTo(200);
        JsonNode in = body(call(s.master(), "POST", "/api/v1/stock/movements", "{\"itemId\":\"" + s.frame()
                + "\",\"kind\":\"ENTRADA\",\"quantity\":3,\"unitCost\":120.5,\"supplierId\":\"" + supplier + "\"}"));
        assertThat(in.path("quantity").asInt()).isEqualTo(3);
        UUID sale = sale(s, 3);
        assertThat(stockOf(s, s.frame())).isEqualTo(2);
        JsonNode items = body(call(s.master(), "GET", "/api/v1/stock", null));
        assertThat(items.get(0).path("low").asBoolean()).isTrue(); // 2 <= mínimo 2
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE item_id=?", Integer.class, s.lens())).isZero();
        assertThat(status(call(s.master(), "PATCH", "/api/v1/sales/" + sale + "/status", "{\"status\":\"CANCELLED\"}"))).isEqualTo(200);
        assertThat(stockOf(s, s.frame())).isEqualTo(3);
        JsonNode history = body(call(s.master(), "GET", "/api/v1/stock/items/" + s.frame() + "/movements", null));
        assertThat(history).hasSize(3);
        JsonNode entry = null;
        for (JsonNode m : history) if (m.path("kind").asText().equals("ENTRADA")) entry = m;
        assertThat(entry.path("supplier").asText()).isEqualTo("Distribuidora");
        assertThat(entry.path("unit_cost").decimalValue()).isEqualByComparingTo("120.50");
        assertThat(status(call(s.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + s.frame() + "\",\"kind\":\"AJUSTE\",\"quantity\":-1}"))).isEqualTo(400); // ajuste pede motivo
        assertThat(status(call(s.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + s.frame() + "\",\"kind\":\"ENTRADA\",\"quantity\":-1}"))).isEqualTo(400);
        assertThat(status(call(s.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + s.frame() + "\",\"kind\":\"SAIDA_VENDA\",\"quantity\":-1}"))).isEqualTo(400);
        assertThat(body(call(s.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + s.frame() + "\",\"kind\":\"AJUSTE\",\"quantity\":-1,\"note\":\"Avaria\"}")).path("quantity").asInt())
                .isEqualTo(2);
    }

    @Test
    void garantiaDaVendaApareceComDataLimite() throws Exception {
        Shop s = seed();
        UUID sale = sale(s, 12);
        assertThat(status(call(s.master(), "POST", "/api/v1/sales", "{\"customerId\":\"" + s.customer() + "\",\"lensId\":\"" + s.lens()
                + "\",\"frameId\":\"" + s.frame() + "\",\"amount\":500,\"paymentMethod\":\"PIX\",\"installments\":1,"
                + "\"passFee\":false,\"replacementMonths\":12,\"warrantyMonths\":99}"))).isEqualTo(400);
        JsonNode list = body(call(s.master(), "GET", "/api/v1/sales", null));
        String soldOn = list.get(0).path("sold_on").asText();
        assertThat(list.get(0).path("id").asText()).isEqualTo(sale.toString());
        assertThat(list.get(0).path("warranty_until").asText())
                .isEqualTo(java.time.LocalDate.parse(soldOn).plusMonths(12).toString());
    }

    @Test
    void ordemDeServicoSegueOFluxoComNumeroEHistorico() throws Exception {
        Shop s = seed(), other = seed();
        UUID sale = sale(s, null);
        JsonNode first = body(call(s.master(), "POST", "/api/v1/service-orders", "{\"kind\":\"LENTES\",\"saleId\":\"" + sale
                + "\",\"description\":\"Lentes multifocais OD -1,25 / OE -1,50\",\"expectedOn\":\"2026-01-10\"}"));
        assertThat(first.path("number").asLong()).isEqualTo(1);
        JsonNode second = body(call(s.master(), "POST", "/api/v1/service-orders", "{\"kind\":\"CONSERTO\",\"customerId\":\""
                + s.customer() + "\",\"description\":\"Trocar plaquetas\",\"price\":30}"));
        assertThat(second.path("number").asLong()).isEqualTo(2);
        UUID order = UUID.fromString(first.path("id").asText());
        assertThat(status(call(s.master(), "PATCH", "/api/v1/service-orders/" + order + "/status", "{\"status\":\"ENTREGUE\"}")))
                .isEqualTo(400); // não pula etapas
        assertThat(status(call(s.master(), "PATCH", "/api/v1/service-orders/" + order + "/status", "{\"status\":\"ENVIADA\"}")))
                .isEqualTo(200);
        JsonNode list = body(call(s.master(), "GET", "/api/v1/service-orders?status=EM_ANDAMENTO", null));
        assertThat(list).hasSize(2);
        JsonNode lentes = list.get(0).path("number").asLong() == 1 ? list.get(0) : list.get(1);
        assertThat(lentes.path("late").asBoolean()).isTrue(); // previsão já passou
        assertThat(lentes.path("customer").asText()).isEqualTo("Cliente OS");
        for (String next : new String[]{"RECEBIDA", "ENTREGUE"})
            assertThat(status(call(s.master(), "PATCH", "/api/v1/service-orders/" + order + "/status", "{\"status\":\"" + next + "\"}")))
                    .isEqualTo(200);
        assertThat(status(call(s.master(), "PATCH", "/api/v1/service-orders/" + order + "/status", "{\"status\":\"CANCELADA\"}")))
                .isEqualTo(400);
        assertThat(body(call(s.master(), "GET", "/api/v1/service-orders/" + order + "/events", null))).hasSize(4);
        // Isolamento: venda, cliente e OS de outra ótica.
        assertThat(status(call(other.master(), "POST", "/api/v1/service-orders", "{\"kind\":\"LENTES\",\"saleId\":\"" + sale
                + "\",\"description\":\"x\"}"))).isEqualTo(404);
        assertThat(status(call(other.master(), "POST", "/api/v1/service-orders", "{\"kind\":\"CONSERTO\",\"customerId\":\""
                + s.customer() + "\",\"description\":\"x\"}"))).isEqualTo(404);
        assertThat(status(call(other.master(), "PATCH", "/api/v1/service-orders/" + order + "/status", "{\"status\":\"CANCELADA\"}")))
                .isEqualTo(404);
        assertThat(body(call(other.master(), "GET", "/api/v1/service-orders", null))).isEmpty();
        assertThat(status(call(s.master(), "POST", "/api/v1/service-orders", "{\"kind\":\"CONSERTO\",\"description\":\"x\"}")))
                .isEqualTo(400);
    }

    @Test
    void estoqueDeOutraOticaNaoEAlcancavel() throws Exception {
        Shop a = seed(), b = seed();
        call(a.master(), "PATCH", "/api/v1/stock/items/" + a.frame(), "{\"trackStock\":true,\"minStock\":0}");
        assertThat(status(call(b.master(), "PATCH", "/api/v1/stock/items/" + a.frame(), "{\"trackStock\":false}"))).isEqualTo(404);
        assertThat(status(call(b.master(), "POST", "/api/v1/stock/movements",
                "{\"itemId\":\"" + a.frame() + "\",\"kind\":\"ENTRADA\",\"quantity\":5}"))).isEqualTo(404);
        assertThat(status(call(b.master(), "GET", "/api/v1/stock/items/" + a.frame() + "/movements", null))).isEqualTo(404);
        UUID supplierA = UUID.fromString(body(call(a.master(), "POST", "/api/v1/suppliers", "{\"name\":\"Forn A\"}")).path("id").asText());
        call(b.master(), "PATCH", "/api/v1/stock/items/" + b.frame(), "{\"trackStock\":true,\"minStock\":0}");
        assertThat(status(call(b.master(), "POST", "/api/v1/stock/movements", "{\"itemId\":\"" + b.frame()
                + "\",\"kind\":\"ENTRADA\",\"quantity\":5,\"supplierId\":\"" + supplierA + "\"}"))).isEqualTo(404);
        assertThat(body(call(b.master(), "GET", "/api/v1/stock", null)).toString()).doesNotContain(a.frame().toString());
    }
}
