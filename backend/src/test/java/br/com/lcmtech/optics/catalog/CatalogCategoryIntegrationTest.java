package br.com.lcmtech.optics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogCategoryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String shop() {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Master',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)", optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        return email;
    }

    private int send(String who, String method, String path, String body) throws Exception {
        var req = ("POST".equals(method) ? post(path) : patch(path)).with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private String create(String who, String body) throws Exception {
        String out = mvc.perform(post("/api/v1/catalog").with(user(who)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(out).path("id").asText();
    }

    private JsonNode list(String who, String kind) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/catalog?kind=" + kind).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void cadastraEditaEValidaTipoDeLenteECategoriaDeArmacao() throws Exception {
        String master = shop();
        String lens = create(master, "{\"kind\":\"LENS\",\"brand\":\"Essilor\",\"model\":\"Varilux\",\"category\":\"MULTIFOCAL\"}");
        String frame = create(master, "{\"kind\":\"FRAME\",\"brand\":\"Ray-Ban\",\"category\":\"SOLAR\"}");
        String legacy = create(master, "{\"kind\":\"FRAME\",\"brand\":\"Antiga\"}");
        assertThat(list(master, "LENS").get(0).path("category").asText()).isEqualTo("MULTIFOCAL");
        assertThat(send(master, "POST", "/api/v1/catalog", "{\"kind\":\"LENS\",\"brand\":\"X\",\"category\":\"SOLAR\"}"))
                .isEqualTo(400);
        assertThat(send(master, "PATCH", "/api/v1/catalog/" + frame, "{\"brand\":\"Ray-Ban\",\"category\":\"MONOFOCAL\"}"))
                .isEqualTo(400);
        assertThat(send(master, "PATCH", "/api/v1/catalog/" + legacy, "{\"brand\":\"Antiga Ltda\",\"model\":\"\",\"category\":\"GRAU\"}"))
                .isEqualTo(200);
        JsonNode frames = list(master, "FRAME");
        assertThat(frames.toString()).contains("Antiga Ltda").contains("GRAU");
        assertThat(lens).isNotBlank();
        JsonNode options = json.readTree(mvc.perform(get("/api/v1/catalog/categories").with(user(master)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(options.path("LENS").path("MULTIFOCAL").asText()).isEqualTo("Multifocal (progressiva)");
        assertThat(options.path("FRAME").size()).isEqualTo(5);
    }

    @Test
    void outraOticaNaoEditaNemDesativaOItem() throws Exception {
        String first = shop(), second = shop();
        String frame = create(first, "{\"kind\":\"FRAME\",\"brand\":\"Ray-Ban\",\"category\":\"SOLAR\"}");
        assertThat(send(second, "PATCH", "/api/v1/catalog/" + frame, "{\"brand\":\"Invasor\",\"category\":\"GRAU\"}"))
                .isEqualTo(404);
        assertThat(send(second, "PATCH", "/api/v1/catalog/" + frame + "/active", "{\"active\":false}")).isEqualTo(404);
        assertThat(list(second, "FRAME").size()).isZero();
        assertThat(list(first, "FRAME").get(0).path("brand").asText()).isEqualTo("Ray-Ban");
        assertThat(list(first, "FRAME").get(0).path("active").asBoolean()).isTrue();
    }
}
