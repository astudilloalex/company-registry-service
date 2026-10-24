package com.alexastudillo.companyregistry.application.outbox;

import io.smallrye.mutiny.Uni;

public interface ConfirmedPublisher {
    /** Completes only after the broker has acknowledged this message. */
    Uni<Void> publish(StoredEvent event);
}
