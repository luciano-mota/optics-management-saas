package br.com.lcmtech.optics.visits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VisitStatusTest {
    @Test
    void segueFluxoCompletoDeVisitaConvertida() {
        VisitStatus state = VisitStatus.REQUESTED
                .transitionTo(VisitStatus.CLAIMED)
                .transitionTo(VisitStatus.CONFIRMED)
                .transitionTo(VisitStatus.VISITED)
                .transitionTo(VisitStatus.CONVERTED);
        assertThat(state).isEqualTo(VisitStatus.CONVERTED);
    }

    @Test
    void naoPodeConverterSemAtenderOuReabrirFinalizada() {
        assertThatThrownBy(() -> VisitStatus.REQUESTED.transitionTo(VisitStatus.CONVERTED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(VisitStatus.NOT_CONVERTED.mayTransitionTo(VisitStatus.CONVERTED)).isFalse();
        assertThat(VisitStatus.CONFIRMED.mayTransitionTo(VisitStatus.CANCELLED)).isTrue();
    }
}
