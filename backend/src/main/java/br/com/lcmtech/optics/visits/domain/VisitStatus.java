package br.com.lcmtech.optics.visits.domain;

/**
 * O histórico deve gravar responsável, data/hora e transição em cada passo.
 * Não transformar lead em cliente até converter em venda de fato.
 */
public enum VisitStatus {
    REQUESTED,
    CLAIMED,
    CONFIRMED,
    VISITED,
    CONVERTED,
    NOT_CONVERTED,
    CANCELLED;

    public boolean mayTransitionTo(VisitStatus target) {
        if (target == null || target == this) return false;
        return switch (this) {
            case REQUESTED -> target == CLAIMED || target == CANCELLED;
            case CLAIMED -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == VISITED || target == CANCELLED;
            case VISITED -> target == CONVERTED || target == NOT_CONVERTED;
            case CONVERTED, NOT_CONVERTED, CANCELLED -> false;
        };
    }

    public VisitStatus transitionTo(VisitStatus target) {
        if (!mayTransitionTo(target)) {
            throw new IllegalStateException("Transição inválida de " + this + " para " + target);
        }
        return target;
    }
}
