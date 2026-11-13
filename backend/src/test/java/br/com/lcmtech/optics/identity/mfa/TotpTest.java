package br.com.lcmtech.optics.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {
    // Vetores do RFC 6238 (SHA1, segredo ASCII "12345678901234567890"), últimos 6 dígitos.
    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void geraOsCodigosDoRfc6238() {
        assertThat(SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void aceitaUmIntervaloDeFolgaERecusaCodigoJaUsado() {
        Instant now = Instant.ofEpochSecond(1111111109);
        long step = Totp.step(now);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 2), now, null)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, "081 804", now, null)).isEqualTo(step);
        assertThat(Totp.verify(SECRET, "081804", now, step)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, "abc", now, null)).isEqualTo(-1);
    }

    @Test
    void segredoNovoTem160Bits() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.decodeBase32(secret)).hasSize(20);
    }
}
