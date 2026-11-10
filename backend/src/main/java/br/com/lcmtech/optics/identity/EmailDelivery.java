package br.com.lcmtech.optics.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Mensagens transacionais pela API do Brevo (padrão do PhysioEvo, BREVO_API_KEY) ou por SMTP
 * (padrão do Ecclesia, MAIL_*). Sem nenhum dos dois, nunca finge que o e-mail foi enviado.
 */
@Service
public class EmailDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(EmailDelivery.class);
    static final URI BREVO_ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    private final ObjectProvider<JavaMailSender> providers;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Value("${optics.mail.from:nao-responda@lcmtech.com.br}") private String from;
    @Value("${optics.mail.from-name:Optics Management}") private String fromName;
    @Value("${optics.mail.brevo-api-key:}") private String brevoApiKey;
    @Value("${spring.mail.host:}") private String host;
    @Value("${spring.mail.username:}") private String username;
    @Value("${spring.mail.password:}") private String password;

    public EmailDelivery(ObjectProvider<JavaMailSender> providers, ObjectMapper json) {
        this.providers = providers;
        this.json = json;
    }

    private boolean brevoApi() { return brevoApiKey != null && !brevoApiKey.isBlank(); }

    private boolean smtp() {
        return providers.getIfAvailable() != null && !host.isBlank() && !username.isBlank() && !password.isBlank();
    }

    public boolean available() { return brevoApi() || smtp(); }

    public void send(String to, String subject, String body) {
        if (brevoApi()) {
            sendWithBrevoApi(to, subject, body);
            return;
        }
        if (!smtp()) throw new IllegalStateException("O envio de e-mails ainda não foi configurado no ambiente.");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        providers.getObject().send(message);
    }

    private void sendWithBrevoApi(String to, String subject, String body) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "sender", Map.of("email", from, "name", fromName),
                    "to", List.of(Map.of("email", to)),
                    "subject", subject,
                    "textContent", body));
            HttpRequest request = HttpRequest.newBuilder(BREVO_ENDPOINT)
                    .timeout(Duration.ofSeconds(20))
                    .header("api-key", brevoApiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Nunca registra destinatário, conteúdo ou chave: só o status devolvido pelo Brevo.
                LOG.warn("Brevo recusou o envio de e-mail (HTTP {}).", response.statusCode());
                throw new IllegalStateException("Não foi possível enviar o e-mail agora. Tente novamente.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio de e-mail interrompido.");
        } catch (java.io.IOException failure) {
            LOG.warn("Falha de comunicação com o Brevo: {}", failure.getClass().getSimpleName());
            throw new IllegalStateException("Não foi possível enviar o e-mail agora. Tente novamente.");
        }
    }
}
