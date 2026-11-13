package br.com.lcmtech.optics.push;

import br.com.lcmtech.optics.security.OpticContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Notificações push no aparelho de quem ativou, mesmo com o app fechado. O texto é genérico (nunca nome, CPF ou
 * receita de cliente). O servidor só chama os serviços de push conhecidos dos navegadores: o endereço vem do
 * navegador e não pode virar porta para o servidor acessar outro lugar (SSRF).
 */
@Service
public class PushService {
    private static final Logger LOG = LoggerFactory.getLogger(PushService.class);
    /** Serviços de push dos navegadores (Chrome/Edge/Android, Firefox, Safari, Windows). */
    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            "push.services.mozilla.com", "web.push.apple.com", "push.apple.com", "notify.windows.com");
    private static final int MAX_FAILURES = 5;

    public record Message(String title, String body, String url, String tag) {}

    private record Keys(byte[] publicKey, ECPrivateKey privateKey) {}

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final TaskExecutor executor;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String subject;
    private final String configuredPublic;
    private final String configuredPrivate;
    private final boolean allowLocal;
    private volatile Keys keys;

    public PushService(JdbcTemplate jdbc, OpticContext context, TaskExecutor executor, ObjectMapper json,
                       @Value("${optics.push.subject:mailto:contato@lcmtech.com.br}") String subject,
                       @Value("${optics.push.vapid-public-key:}") String configuredPublic,
                       @Value("${optics.push.vapid-private-key:}") String configuredPrivate,
                       @Value("${optics.push.allow-local-endpoints:false}") boolean allowLocal) {
        this.jdbc = jdbc;
        this.context = context;
        this.executor = executor;
        this.json = json;
        this.subject = subject;
        this.configuredPublic = configuredPublic;
        this.configuredPrivate = configuredPrivate;
        this.allowLocal = allowLocal;
    }

    /** Chave pública que o navegador usa para se inscrever. */
    public String publicKey() {
        return WebPushCrypto.b64(keys().publicKey());
    }

    private Keys keys() {
        Keys current = keys;
        if (current != null) return current;
        synchronized (this) {
            if (keys != null) return keys;
            if (!configuredPublic.isBlank() && !configuredPrivate.isBlank()) {
                keys = new Keys(WebPushCrypto.unb64(configuredPublic),
                        WebPushCrypto.privateKey(WebPushCrypto.unb64(configuredPrivate)));
                return keys;
            }
            List<String[]> stored = jdbc.query("SELECT public_key,private_key FROM platform_push_keys WHERE id=1",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2)});
            if (stored.isEmpty()) {
                KeyPair pair = WebPushCrypto.newKeyPair();
                jdbc.update("INSERT INTO platform_push_keys(id,public_key,private_key) VALUES(1,?,?) ON CONFLICT (id) DO NOTHING",
                        WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) pair.getPublic())),
                        WebPushCrypto.b64(WebPushCrypto.encode((ECPrivateKey) pair.getPrivate())));
                stored = jdbc.query("SELECT public_key,private_key FROM platform_push_keys WHERE id=1",
                        (rs, n) -> new String[]{rs.getString(1), rs.getString(2)});
            }
            keys = new Keys(WebPushCrypto.unb64(stored.get(0)[0]), WebPushCrypto.privateKey(WebPushCrypto.unb64(stored.get(0)[1])));
            return keys;
        }
    }

    /** Liga o aparelho à conta (o mesmo aparelho, com outra pessoa logada, passa para a nova conta). */
    public void subscribe(UUID account, String endpoint, String p256dh, String auth, String device) {
        requireEndpoint(endpoint);
        byte[] key, secret;
        try {
            key = WebPushCrypto.unb64(p256dh);
            secret = WebPushCrypto.unb64(auth);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Chaves do aparelho inválidas.");
        }
        if (key.length != 65 || key[0] != 4 || secret.length != 16) throw new IllegalArgumentException("Chaves do aparelho inválidas.");
        WebPushCrypto.publicKey(key);
        jdbc.update("""
                INSERT INTO push_subscriptions(id,account_id,endpoint,p256dh,auth,device) VALUES(?,?,?,?,?,?)
                ON CONFLICT (endpoint) DO UPDATE SET account_id=EXCLUDED.account_id, p256dh=EXCLUDED.p256dh,
                    auth=EXCLUDED.auth, device=EXCLUDED.device, failures=0
                """, UUID.randomUUID(), account, endpoint, p256dh, auth,
                device == null ? null : device.substring(0, Math.min(120, device.length())));
    }

    public void unsubscribe(UUID account, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE account_id=? AND endpoint=?", account, endpoint);
    }

    public Map<String, Object> status(UUID account, String endpoint) {
        Integer devices = jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE account_id=?", Integer.class, account);
        Integer mine = endpoint == null ? 0 : jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE account_id=? AND endpoint=?", Integer.class, account, endpoint);
        return Map.of("devices", devices == null ? 0 : devices, "subscribed", mine != null && mine > 0);
    }

    void requireEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Endereço de notificação inválido.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean local = allowLocal && "http".equals(uri.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1"));
        boolean known = "https".equals(uri.getScheme()) && uri.getPort() == -1
                && PUSH_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!local && !known) throw new IllegalArgumentException("Este navegador usa um serviço de notificação não suportado.");
    }

    /** Envia para todas as pessoas da ótica com a permissão (em segundo plano). */
    public void notifyOptic(UUID optic, String permission, Message message) {
        executor.execute(() -> {
            try {
                List<UUID> accounts = jdbc.query("""
                        SELECT a.id FROM optic_memberships m JOIN accounts a ON a.id=m.account_id
                        WHERE m.optic_id=? AND m.active AND a.active
                        """, (rs, n) -> (UUID) rs.getObject(1), optic);
                for (UUID account : accounts)
                    if (permission == null || context.can(account, optic, permission)) sendToAccount(account, message);
            } catch (RuntimeException failure) {
                LOG.warn("Falha ao enviar notificações push: {}", failure.getClass().getSimpleName());
            }
        });
    }

    /** Aviso da LCM DEV TECH para uma ótica ou para todas (opticId nulo). */
    public void notifyPlatformNotice(UUID opticId, Message message) {
        executor.execute(() -> {
            try {
                List<UUID> accounts = jdbc.query("""
                        SELECT DISTINCT a.id FROM optic_memberships m JOIN accounts a ON a.id=m.account_id
                        JOIN optics o ON o.id=m.optic_id
                        WHERE m.active AND a.active AND o.active AND (?::uuid IS NULL OR m.optic_id=?::uuid)
                        """, (rs, n) -> (UUID) rs.getObject(1), opticId, opticId);
                for (UUID account : accounts) sendToAccount(account, message);
            } catch (RuntimeException failure) {
                LOG.warn("Falha ao enviar notificações push: {}", failure.getClass().getSimpleName());
            }
        });
    }

    /** Envia agora para os aparelhos da conta; devolve quantos aceitaram. */
    public int sendToAccount(UUID account, Message message) {
        List<Map<String, Object>> subscriptions = jdbc.queryForList(
                "SELECT id,endpoint,p256dh,auth FROM push_subscriptions WHERE account_id=?", account);
        int delivered = 0;
        for (Map<String, Object> s : subscriptions) if (send(s, message)) delivered++;
        return delivered;
    }

    private boolean send(Map<String, Object> s, Message message) {
        UUID id = (UUID) s.get("id");
        String endpoint = (String) s.get("endpoint");
        try {
            requireEndpoint(endpoint);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("title", message.title());
            data.put("body", message.body());
            data.put("url", message.url() == null ? "/" : message.url());
            data.put("tag", message.tag() == null ? "optics" : message.tag());
            byte[] body = WebPushCrypto.encrypt(json.writeValueAsString(data).getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.unb64((String) s.get("p256dh")), WebPushCrypto.unb64((String) s.get("auth")));
            Keys k = keys();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", WebPushCrypto.vapidAuthorization(endpoint, subject, k.publicKey(),
                            k.privateKey(), Instant.now().getEpochSecond()))
                    .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400").header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                jdbc.update("UPDATE push_subscriptions SET last_success_at=now(), failures=0 WHERE id=?", id);
                return true;
            }
            if (status == 404 || status == 410) { // aparelho desinscrito ou expirado
                jdbc.update("DELETE FROM push_subscriptions WHERE id=?", id);
                return false;
            }
            fail(id);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            fail(id);
            return false;
        }
    }

    private void fail(UUID id) {
        jdbc.update("UPDATE push_subscriptions SET failures=failures+1 WHERE id=?", id);
        jdbc.update("DELETE FROM push_subscriptions WHERE id=? AND failures>=?", id, MAX_FAILURES);
    }
}
