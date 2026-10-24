package com.alexastudillo.companyregistry.application.outbox;

public interface DeliveryObserver {
    enum Signal { CONFIRMED, PUBLISHED, RETRY, FAILED, STALE, TRANSITION_UNCERTAIN }
    void observe(DeliveryStore.Claim claim, Signal signal, DeliveryFailure.Code error);
}
