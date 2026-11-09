package br.com.lcmtech.optics.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lcmtech.optics.platform.domain.SubscriptionAccessPolicy.Mode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubscriptionAccessPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void cortesiaNaoTemPrazoEscondeAssinaturaENaoCobra() {
        SubscriptionAccessPolicy policy = new SubscriptionAccessPolicy(Mode.COMPLIMENTARY, null, false);
        assertThat(policy.canUseApplicationAt(NOW.plusSeconds(5_000_000))).isTrue();
        assertThat(policy.shouldDisplaySubscriptionTab()).isFalse();
        assertThat(policy.shouldChargeCustomer()).isFalse();
    }

    @Test
    void trialExpiraNoLimiteExatoESeteDiasSaoConfiguradosNoBanco() {
        SubscriptionAccessPolicy policy = new SubscriptionAccessPolicy(Mode.TRIAL, NOW.plusSeconds(7 * 86400L), false);
        assertThat(policy.canUseApplicationAt(NOW)).isTrue();
        assertThat(policy.canUseApplicationAt(NOW.plusSeconds(7 * 86400L))).isFalse();
        assertThat(policy.shouldDisplaySubscriptionTab()).isTrue();
        assertThat(policy.shouldChargeCustomer()).isFalse();
    }

    @Test
    void pagoExigeAssinaturaAtiva() {
        SubscriptionAccessPolicy policy = new SubscriptionAccessPolicy(Mode.PAID, null, false);
        assertThat(policy.canUseApplicationAt(NOW)).isFalse();
        assertThat(policy.shouldChargeCustomer()).isTrue();
    }
}
