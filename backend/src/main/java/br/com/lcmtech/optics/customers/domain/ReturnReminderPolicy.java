package br.com.lcmtech.optics.customers.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Datas calculadas a partir da venda, nunca a partir da entrega.
 * Uma rotina de notificações deve persistir um idempotency key por cliente,
 * venda, marco e canal para não enviar a mesma saudação duas vezes.
 */
public final class ReturnReminderPolicy {
    private static final List<Integer> MILESTONES = List.of(3, 6, 9, 12);

    private ReturnReminderPolicy() {}

    public static List<Milestone> milestonesFrom(LocalDate saleDate) {
        Objects.requireNonNull(saleDate, "saleDate");
        return MILESTONES.stream()
                .map(months -> new Milestone(months, saleDate.plusMonths(months)))
                .toList();
    }

    public static List<Milestone> milestonesDueOn(LocalDate saleDate, LocalDate targetDate) {
        Objects.requireNonNull(targetDate, "targetDate");
        return milestonesFrom(saleDate).stream()
                .filter(milestone -> milestone.date().equals(targetDate))
                .toList();
    }

    public static LocalDate initialReplacementDate(LocalDate saleDate, int cadenceMonths) {
        Objects.requireNonNull(saleDate, "saleDate");
        if (cadenceMonths != 6 && cadenceMonths != 12) {
            throw new IllegalArgumentException("A frequência deve ser 6 ou 12 meses.");
        }
        return saleDate.plusMonths(cadenceMonths);
    }

    public record Milestone(int month, LocalDate date) {}
}
