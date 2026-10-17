package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import java.util.concurrent.CancellationException;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.Test;

class PersistenceFailuresTest {
    @Test void preservesBusinessFailuresAndCancellationButClassifiesKnownTechnicalFailures() {
        assertFalse(PersistenceFailures.technical(new IllegalArgumentException("domain-failure")));
        assertFalse(PersistenceFailures.technical(new CancellationException()));
        assertFalse(PersistenceFailures.technical(new PersistenceFailure(PersistenceFailure.Kind.INVALID_DATA, null)));
        assertEquals(PersistenceFailure.Kind.STALE_VERSION, PersistenceFailures.translate(new StaleObjectStateException("Company", "id")).kind());
        assertEquals(PersistenceFailure.Kind.UNAVAILABLE, PersistenceFailures.translate(new java.net.ConnectException()).kind());
        assertEquals(PersistenceFailure.Kind.UNAVAILABLE, PersistenceFailures.translate(new java.util.concurrent.TimeoutException()).kind());
    }
}
