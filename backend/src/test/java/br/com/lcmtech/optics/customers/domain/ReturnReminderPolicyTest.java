package br.com.lcmtech.optics.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnReminderPolicyTest {
    @Test
    void calculaTresSeisNoveEDozeMesesAPartirDaVenda() {
        LocalDate sale = LocalDate.of(2026, 11, 9);
        assertThat(ReturnReminderPolicy.milestonesFrom(sale))
                .extracting(ReturnReminderPolicy.Milestone::date)
                .containsExactly(LocalDate.of(2027, 2, 9), LocalDate.of(2027, 5, 9),
                        LocalDate.of(2027, 8, 9), LocalDate.of(2027, 11, 9));
        assertThat(ReturnReminderPolicy.milestonesDueOn(sale, LocalDate.of(2027, 5, 9)))
                .extracting(ReturnReminderPolicy.Milestone::month).isEqualTo(List.of(6));
    }

    @Test
    void trocaDeSeisOuDozeMesesEValidaAEscolha() {
        LocalDate sale = LocalDate.of(2026, 1, 31);
        assertThat(ReturnReminderPolicy.initialReplacementDate(sale, 6))
                .isEqualTo(LocalDate.of(2026, 7, 31));
        assertThatThrownBy(() -> ReturnReminderPolicy.initialReplacementDate(sale, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
