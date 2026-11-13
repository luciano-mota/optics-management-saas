package br.com.lcmtech.optics.visits;

import br.com.lcmtech.optics.identity.EmailDelivery;
import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.OpticClock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Avisa por e-mail todos os funcionários que atendem visitas quando chega um pedido pelo site.
 * O e-mail não leva dados do cliente (nome, telefone, endereço, receita): só o horário pedido e o
 * convite para abrir o sistema. No painel, o sino e a notificação do navegador também avisam.
 */
@Service
public class VisitNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(VisitNotifier.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");

    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final EmailDelivery mail;
    private final OpticClock clock;
    private final TaskExecutor executor;
    private final br.com.lcmtech.optics.push.PushService push;
    @Value("${optics.public-base-url:https://optics-management-hml.onrender.com}") private String baseUrl;

    public VisitNotifier(JdbcTemplate jdbc, OpticContext context, EmailDelivery mail, OpticClock clock,
                         TaskExecutor executor, br.com.lcmtech.optics.push.PushService push) {
        this.push = push;
        this.jdbc = jdbc;
        this.context = context;
        this.mail = mail;
        this.clock = clock;
        this.executor = executor;
    }

    /** Envio em segundo plano: o pedido do cliente no site não espera pelos e-mails. */
    public void newRequest(UUID optic, String opticName, Instant appointmentAt) {
        // Push no aparelho de quem ativou (texto genérico, sem dados do cliente).
        push.notifyOptic(optic, "VISITS_READ", new br.com.lcmtech.optics.push.PushService.Message(
                "Novo pedido de visita", "A " + opticName + " recebeu um pedido de atendimento em domicílio pelo site.",
                "/", "optics-visit-" + optic));
        if (!mail.available()) return;
        executor.execute(() -> {
            try {
                String when = WHEN.format(appointmentAt.atZone(clock.zone(optic)));
                var recipients = jdbc.query("""
                        SELECT a.id,a.email FROM optic_memberships m JOIN accounts a ON a.id=m.account_id
                        WHERE m.optic_id=? AND m.active AND a.active
                        """, (rs, n) -> new Object[]{rs.getObject(1), rs.getString(2)}, optic);
                for (Object[] person : recipients) {
                    if (!context.can((UUID) person[0], optic, "VISITS_READ")) continue;
                    mail.send((String) person[1], "Novo pedido de atendimento em domicílio",
                            "Olá! A " + opticName + " recebeu pelo site um pedido de atendimento em domicílio "
                            + "para " + when + ".\n\nAbra o Optics Management para confirmar com o cliente:\n"
                            + baseUrl.replaceAll("/+$", "") + "\n\nEste aviso não inclui dados do cliente.");
                }
            } catch (RuntimeException failure) {
                LOG.warn("Não foi possível avisar a equipe sobre a nova visita: {}", failure.getClass().getSimpleName());
            }
        });
    }
}
