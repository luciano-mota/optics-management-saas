package br.com.lcmtech.optics.sales.domain;

/**
 * Estado da entrega do produto. Pagamento, visita domiciliar e compra têm ciclos
 * distintos; não devem usar este status para inferir que houve recebimento financeiro.
 */
public enum SaleStatus {
    AWAITING_PRODUCTION,
    READY_FOR_DELIVERY,
    DELIVERED,
    CANCELLED;

    public boolean mayTransitionTo(SaleStatus next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case AWAITING_PRODUCTION -> next == READY_FOR_DELIVERY || next == CANCELLED;
            case READY_FOR_DELIVERY -> next == DELIVERED || next == CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };
    }

    public SaleStatus transitionTo(SaleStatus next) {
        if (!mayTransitionTo(next)) {
            throw new IllegalStateException("Transição inválida de " + this + " para " + next);
        }
        return next;
    }
}
