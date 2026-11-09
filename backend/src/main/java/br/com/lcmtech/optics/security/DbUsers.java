package br.com.lcmtech.optics.security;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Repositório de identidades do login global, nunca vincula sessão a um tenant arbitrário. */
@Service
public class DbUsers implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final boolean verificationRequired;
    public DbUsers(JdbcTemplate jdbc,org.springframework.beans.factory.annotation.Value("${optics.email-verification-required:false}") boolean verificationRequired) {
        this.jdbc = jdbc;this.verificationRequired=verificationRequired;
    }
    @Override public UserDetails loadUserByUsername(String username) {
        if (username == null) throw new UsernameNotFoundException("Credenciais inválidas");
        List<UserDetails> users = jdbc.query(
                "SELECT email,password_hash,active,email_verified_at FROM accounts WHERE lower(email)=lower(?)",
                (rs, i) -> User.withUsername(rs.getString("email"))
                        .password(rs.getString("password_hash"))
                        .disabled(!rs.getBoolean("active") || (verificationRequired && rs.getTimestamp("email_verified_at")==null))
                        .roles("USER").build(), username.trim());
        if (users.isEmpty()) throw new UsernameNotFoundException("Credenciais inválidas");
        return users.get(0);
    }
}
