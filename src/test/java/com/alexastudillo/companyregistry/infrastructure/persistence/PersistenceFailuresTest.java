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

    @Test void translatesDuplicateConstraintViolations() {
        var codeViolation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("error", "23505"), "uq_companies_tenant_code");
        var translatedCode = PersistenceFailures.translate(codeViolation);
        assertEquals(PersistenceFailure.Kind.DUPLICATE, translatedCode.kind());
        assertEquals(PersistenceFailure.Uniqueness.COMPANY_CODE, translatedCode.uniqueness());

        var partyViolation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("error", "23505"), "uq_companies_tenant_party");
        var translatedParty = PersistenceFailures.translate(partyViolation);
        assertEquals(PersistenceFailure.Kind.DUPLICATE, translatedParty.kind());
        assertEquals(PersistenceFailure.Uniqueness.COMPANY_PARTY, translatedParty.uniqueness());

        var idempotencyViolation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("error", "23505"), "pk_company_idempotency_records");
        var translatedIdempotency = PersistenceFailures.translate(idempotencyViolation);
        assertEquals(PersistenceFailure.Kind.DUPLICATE, translatedIdempotency.kind());
        assertEquals(PersistenceFailure.Uniqueness.COMPLETION_KEY, translatedIdempotency.uniqueness());

        var unknownViolation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("error", "23505"), "other_constraint");
        var translatedUnknown = PersistenceFailures.translate(unknownViolation);
        assertEquals(PersistenceFailure.Kind.UNEXPECTED, translatedUnknown.kind());
        assertEquals(PersistenceFailure.Uniqueness.OTHER, translatedUnknown.uniqueness());

        var nullViolation = new org.hibernate.JDBCException("duplicate", new java.sql.SQLException("error", "23505"));
        var translatedNull = PersistenceFailures.translate(nullViolation);
        assertEquals(PersistenceFailure.Kind.UNEXPECTED, translatedNull.kind());
        assertEquals(PersistenceFailure.Uniqueness.OTHER, translatedNull.uniqueness());
    }

    @Test void translatesSqlFailuresBySqlState() {
        for (var state : new String[] {"40P01", "40001"}) {
            assertEquals(PersistenceFailure.Kind.UNAVAILABLE, PersistenceFailures.translate(
                    new org.hibernate.JDBCException("transient abort", new java.sql.SQLException("abort", state))).kind());
        }
        var lockTimeout = new org.hibernate.JDBCException("timeout", new java.sql.SQLException("lock timeout", "55P03"));
        assertEquals(PersistenceFailure.Kind.UNAVAILABLE, PersistenceFailures.translate(lockTimeout).kind());

        var invalidRef = new org.hibernate.JDBCException("fk", new java.sql.SQLException("fk error", "23503"));
        assertEquals(PersistenceFailure.Kind.INVALID_REFERENCE, PersistenceFailures.translate(invalidRef).kind());

        var invalidData = new org.hibernate.JDBCException("data", new java.sql.SQLException("check violation", "23514"));
        assertEquals(PersistenceFailure.Kind.INVALID_DATA, PersistenceFailures.translate(invalidData).kind());

        var connectionErr = new org.hibernate.JDBCException("conn", new java.sql.SQLException("conn closed", "08006"));
        assertEquals(PersistenceFailure.Kind.UNAVAILABLE, PersistenceFailures.translate(connectionErr).kind());
    }
}
