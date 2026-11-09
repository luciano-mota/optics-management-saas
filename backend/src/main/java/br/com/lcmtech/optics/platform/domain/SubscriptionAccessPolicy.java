package br.com.lcmtech.optics.platform.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Cortesia não é teste prolongado: não há cobrança enquanto o operador mantiver
 * COMPLIMENTARY. O backend deve usar a mesma política que orienta a UI.
 */
public record SubscriptionAccessPolicy(
        Mode mode, Instant trialEndsAt, boolean paidSubscriptionActive) {

    public enum Mode {
        TRIAL, PAID, COMPLIMENTARY
    }

    public SubscriptionAccessPolicy {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.TRIAL) Objects.requireNonNull(trialEndsAt, "trialEndsAt");
    }

    public boolean canUseApplicationAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return switch (mode) {
            case COMPLIMENTARY -> true;
            case TRIAL -> instant.isBefore(trialEndsAt);
            case PAID -> paidSubscriptionActive;
        };
    }

    public boolean shouldDisplaySubscriptionTab() {
        return mode != Mode.COMPLIMENTARY;
    }

    public boolean shouldChargeCustomer() {
        return mode == Mode.PAID;
    }
}
