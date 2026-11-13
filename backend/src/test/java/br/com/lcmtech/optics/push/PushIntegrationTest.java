package br.com.lcmtech.optics.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Um "serviço de push" local recebe o POST, como o do Google faria; o teste abre a mensagem com a chave do aparelho. */
@SpringBootTest(properties = "optics.push.allow-local-endpoints=true")
@AutoConfigureMockMvc
class PushIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired br.com.lcmtech.optics.visits.VisitNotifier visits;

    record Received(String path, String authorization, String encoding, byte[] body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Encoding"), exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(statusByPath.getOrDefault(exchange.getRequestURI().getPath(), 201), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String endpoint(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    record Device(KeyPair keys, byte[] auth, String endpoint) {
        byte[] publicKey() { return WebPushCrypto.encode((ECPublicKey) keys.getPublic()); }
    }

    private record Shop(UUID optic, String email, UUID account) {}

    private Shop shop(String role) {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Pessoa',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica Push',?)", optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, account, role);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        return new Shop(optic, email, account);
    }

    private UUID member(UUID optic, String role) {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Pessoa',now())",
                account, account + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, account, role);
        return account;
    }

    private Device subscribe(String who, String path) throws Exception {
        Device d = new Device(WebPushCrypto.newKeyPair(), WebPushCrypto.randomBytes(16), endpoint(path));
        int status = mvc.perform(post("/api/v1/push/subscriptions").with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).header("User-Agent", "Chrome teste")
                .content(json.writeValueAsString(Map.of("endpoint", d.endpoint(),
                        "keys", Map.of("p256dh", WebPushCrypto.b64(d.publicKey()), "auth", WebPushCrypto.b64(d.auth()))))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return d;
    }

    private JsonNode open(Received r, Device d) throws Exception {
        return json.readTree(WebPushCrypto.decrypt(r.body(), (ECPrivateKey) d.keys().getPrivate(), d.publicKey(), d.auth()));
    }

    private Received await(String path) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (Received r : received) if (r.path().equals(path)) return r;
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    void avisoDeTesteChegaCifradoComVapidValido() throws Exception {
        Shop shop = shop("MASTER");
        String key = json.readTree(mvc.perform(get("/api/v1/push/public-key").with(user(shop.email())))
                .andReturn().getResponse().getContentAsString()).path("publicKey").asText();
        Device device = subscribe(shop.email(), "/device-" + shop.account());
        JsonNode sent = json.readTree(mvc.perform(post("/api/v1/push/test").with(user(shop.email())).with(csrf()))
                .andReturn().getResponse().getContentAsString());
        assertThat(sent.path("sent").asInt()).isEqualTo(1);
        Received r = await("/device-" + shop.account());
        assertThat(r).isNotNull();
        assertThat(r.encoding()).isEqualTo("aes128gcm");
        JsonNode message = open(r, device);
        assertThat(message.path("title").asText()).isEqualTo("Optics Management");
        // Assinatura VAPID confere com a chave pública divulgada aos navegadores.
        String token = r.authorization().substring(8, r.authorization().indexOf(','));
        String[] parts = token.split("\\.");
        assertThat(r.authorization()).endsWith("k=" + key);
        assertThat(WebPushCryptoTest.verify(WebPushCrypto.unb64(key), parts[0] + "." + parts[1], WebPushCrypto.unb64(parts[2]))).isTrue();
        assertThat(new String(WebPushCrypto.unb64(parts[1]), StandardCharsets.UTF_8))
                .contains("\"aud\":\"http://localhost:" + server.getAddress().getPort() + "\"");
        assertThat(jdbc.queryForObject("SELECT last_success_at IS NOT NULL FROM push_subscriptions WHERE account_id=?",
                Boolean.class, shop.account())).isTrue();
    }

    @Test
    void enderecoDesconhecidoERecusadoEAparelhoExpiradoSai() throws Exception {
        Shop shop = shop("MASTER");
        int status = mvc.perform(post("/api/v1/push/subscriptions").with(user(shop.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"https://evil.example.com/x\",\"keys\":{\"p256dh\":\""
                        + WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) WebPushCrypto.newKeyPair().getPublic()))
                        + "\",\"auth\":\"" + WebPushCrypto.b64(WebPushCrypto.randomBytes(16)) + "\"}}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        String path = "/gone-" + shop.account();
        subscribe(shop.email(), path);
        statusByPath.put(path, 410);
        mvc.perform(post("/api/v1/push/test").with(user(shop.email())).with(csrf()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE account_id=?", Integer.class,
                shop.account())).isZero();
    }

    @Test
    void cadaContaSoMexeNosProprosAparelhos() throws Exception {
        Shop a = shop("MASTER"), b = shop("MASTER");
        Device device = subscribe(a.email(), "/a-" + a.account());
        mvc.perform(post("/api/v1/push/subscriptions/remove").with(user(b.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + device.endpoint() + "\"}"));
        JsonNode statusA = json.readTree(mvc.perform(get("/api/v1/push/status").param("endpoint", device.endpoint())
                .with(user(a.email()))).andReturn().getResponse().getContentAsString());
        JsonNode statusB = json.readTree(mvc.perform(get("/api/v1/push/status").param("endpoint", device.endpoint())
                .with(user(b.email()))).andReturn().getResponse().getContentAsString());
        assertThat(statusA.path("subscribed").asBoolean()).isTrue();
        assertThat(statusB.path("subscribed").asBoolean()).isFalse();
        assertThat(statusB.path("devices").asInt()).isZero();
        mvc.perform(post("/api/v1/push/subscriptions/remove").with(user(a.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + device.endpoint() + "\"}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE account_id=?", Integer.class,
                a.account())).isZero();
    }

    @Test
    void pedidoDeVisitaAvisaSoQuemAtendeVisitasESemDadosDoCliente() throws Exception {
        Shop shop = shop("MASTER");
        UUID blocked = member(shop.optic(), "SELLER");
        jdbc.update("INSERT INTO member_permission_overrides(optic_id,account_id,permission,effect) VALUES(?,?,'VISITS_READ','DENY')",
                shop.optic(), blocked);
        Shop other = shop("MASTER");
        Device master = subscribe(shop.email(), "/visit-master-" + shop.account());
        subscribe(blocked + "@example.test", "/visit-blocked-" + blocked);
        subscribe(other.email(), "/visit-other-" + other.account());
        visits.newRequest(shop.optic(), "Ótica Push", Instant.now().plusSeconds(86400));
        Received r = await("/visit-master-" + shop.account());
        assertThat(r).isNotNull();
        JsonNode message = open(r, master);
        assertThat(message.path("title").asText()).isEqualTo("Novo pedido de visita");
        assertThat(message.path("body").asText()).contains("Ótica Push");
        Thread.sleep(300);
        assertThat(received).noneMatch(x -> x.path().equals("/visit-blocked-" + blocked));
        assertThat(received).noneMatch(x -> x.path().equals("/visit-other-" + other.account()));
    }
}
