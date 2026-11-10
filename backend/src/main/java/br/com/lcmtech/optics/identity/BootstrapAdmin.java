package br.com.lcmtech.optics.identity;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mesmo padrão do Ecclesia Finance: acesso inicial do operador via
 * BOOTSTRAP_ADMIN_EMAIL/PASSWORD (Ecclesia) ou PLATFORM_ADMIN_EMAIL/PASSWORD (PhysioEvo),
 * apenas no primeiro uso (ver config.EnvironmentAliases).
 * Nunca publica token, endpoint ou senha padrão; não redefine senha no deploy.
 * Operadores existentes continuam entrando normalmente no login comum.
 */
@Component
public class BootstrapAdmin implements CommandLineRunner {
    private static final Logger LOG=LoggerFactory.getLogger(BootstrapAdmin.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public BootstrapAdmin(JdbcTemplate jdbc,PasswordEncoder passwords,
            @Value("${optics.bootstrap.email:}") String email,
            @Value("${optics.bootstrap.password:}") String password) {
        this.jdbc=jdbc;
        this.passwords=passwords;
        this.bootstrapEmail=email;
        this.bootstrapPassword=password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer operators=jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_operators WHERE active",Integer.class);
        if(operators!=null && operators>0) return;
        if(bootstrapEmail.isBlank() && bootstrapPassword.isBlank()) {
            LOG.info("Operador global: credenciais de bootstrap não configuradas "
                    +"(use BOOTSTRAP_ADMIN_EMAIL/PASSWORD ou PLATFORM_ADMIN_EMAIL/PASSWORD).");
            return;
        }
        if(bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            LOG.warn("Bootstrap do operador requer e-mail e senha; nada foi alterado.");
            return;
        }
        String email=bootstrapEmail.trim().toLowerCase(Locale.ROOT);
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||
                bootstrapPassword.length()<12||bootstrapPassword.length()>128||
                !bootstrapPassword.matches("(?s).*[A-Za-z].*")||
                !bootstrapPassword.matches("(?s).*\\d.*")) {
            LOG.warn("Credenciais de bootstrap inválidas (e-mail válido e senha de 12 a 128 caracteres "
                    +"com letras e números); não foi criado operador.");
            return;
        }
        List<UUID> existing=jdbc.query(
                "SELECT id FROM accounts WHERE lower(email)=? AND active FOR UPDATE",
                (rs,n)->(UUID)rs.getObject(1),email);
        UUID account;
        if(!existing.isEmpty()) {
            account=existing.get(0);
            String existingHash=jdbc.queryForObject(
                "SELECT password_hash FROM accounts WHERE id=?",String.class,account);
            if(!passwords.matches(bootstrapPassword,existingHash)) {
                // Só acontece enquanto não existe nenhum operador: quem define as variáveis do servidor
                // é o dono da plataforma, então a senha das variáveis passa a valer para essa conta.
                jdbc.update("UPDATE accounts SET password_hash=?,email_verified_at=COALESCE(email_verified_at,now()),"
                        +"updated_at=now() WHERE id=?",passwords.encode(bootstrapPassword),account);
                jdbc.update("DELETE FROM spring_session WHERE lower(principal_name)=lower(?)",email);
                LOG.warn("Conta existente do operador recebeu a senha definida nas variáveis do servidor.");
            }
        } else {
            Integer inactive=jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE lower(email)=?",Integer.class,email);
            if(inactive!=null && inactive>0) {
                LOG.warn("Conta de bootstrap inativa; reativação automática bloqueada.");
                return;
            }
            account=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at)
                VALUES(?,?,?,'Administrador LCM Dev Tech',now())
                """,account,email,passwords.encode(bootstrapPassword));
        }
        jdbc.update("INSERT INTO platform_operators(account_id) VALUES(?) "
                +"ON CONFLICT(account_id) DO NOTHING",account);
        jdbc.update("""
            INSERT INTO audit_events(id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,'PLATFORM_OWNER_BOOTSTRAPPED','ACCOUNT',?)
            """,UUID.randomUUID(),account,account);
        LOG.info("Operador global inicial provisionado sem criar ótica.");
    }
}
