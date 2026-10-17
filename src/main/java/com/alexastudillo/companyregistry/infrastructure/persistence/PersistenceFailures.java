package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure.Kind;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure.Uniqueness;
import io.vertx.pgclient.PgException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.net.ConnectException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.hibernate.HibernateException;
import org.hibernate.JDBCException;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;

final class PersistenceFailures {
    private PersistenceFailures() {}
    static boolean technical(Throwable failure) {
        if (failure instanceof PersistenceFailure || failure instanceof CancellationException) return false;
        return failure instanceof HibernateException || failure instanceof PersistenceException
                || failure instanceof PgException || failure instanceof ConnectException || failure instanceof TimeoutException;
    }
    static PersistenceFailure translate(Throwable failure) {
        PersistenceFailure duplicate = translateDuplicate(failure);
        if (duplicate != null) {
            return duplicate;
        }
        return translateGeneral(failure);
    }

    private static PersistenceFailure translateDuplicate(Throwable failure) {
        String constraint = null;
        boolean duplicate = false;
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            var extracted = extractConstraint(cause);
            if (extracted != null) {
                constraint = extracted;
            }
            if (isDuplicateState(cause)) {
                duplicate = true;
            }
        }
        if (!duplicate) {
            return null;
        }
        return createDuplicateFailure(constraint, failure);
    }

    private static String extractConstraint(Throwable cause) {
        if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
            return violation.getConstraintName();
        }
        if (cause instanceof PgException postgres && postgres.getConstraint() != null) {
            return postgres.getConstraint();
        }
        return null;
    }

    private static boolean isDuplicateState(Throwable cause) {
        return "23505".equals(extractSqlState(cause));
    }

    private static PersistenceFailure createDuplicateFailure(String constraint, Throwable failure) {
        var uniqueness = resolveUniqueness(constraint);
        var kind = uniqueness == Uniqueness.OTHER ? Kind.UNEXPECTED : Kind.DUPLICATE;
        return new PersistenceFailure(kind, uniqueness, failure);
    }

    private static Uniqueness resolveUniqueness(String constraint) {
        if (constraint == null) {
            return Uniqueness.OTHER;
        }
        return switch (constraint) {
            case "uq_companies_tenant_code" -> Uniqueness.COMPANY_CODE;
            case "uq_companies_tenant_party" -> Uniqueness.COMPANY_PARTY;
            case "pk_company_idempotency_records" -> Uniqueness.COMPLETION_KEY;
            default -> Uniqueness.OTHER;
        };
    }

    private static PersistenceFailure translateGeneral(Throwable failure) {
        Kind kind = Kind.UNEXPECTED;
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (isStaleVersion(cause)) {
                return new PersistenceFailure(Kind.STALE_VERSION, failure);
            }
            if (isSqlFailure(cause)) {
                return new PersistenceFailure(classifySqlFailure(cause, kind), failure);
            }
            if (isUnavailable(cause)) {
                kind = Kind.UNAVAILABLE;
            }
        }
        return new PersistenceFailure(kind, failure);
    }

    private static boolean isStaleVersion(Throwable cause) {
        return cause instanceof OptimisticLockException || cause instanceof StaleStateException;
    }

    private static boolean isSqlFailure(Throwable cause) {
        return cause instanceof PgException || cause instanceof JDBCException;
    }

    private static boolean isUnavailable(Throwable cause) {
        return cause instanceof ConnectException || cause instanceof TimeoutException;
    }

    private static Kind classifySqlFailure(Throwable cause, Kind fallback) {
        var state = extractSqlState(cause);
        return state != null ? classifySqlState(state) : fallback;
    }

    // Hibernate Reactive may retain SQLSTATE in Hibernate's JDBCException
    // abstraction rather than retaining the original Vert.x PgException.
    private static String extractSqlState(Throwable cause) {
        if (cause instanceof PgException postgres) {
            return postgres.getSqlState();
        }
        if (cause instanceof JDBCException jdbc) {
            return jdbc.getSQLState();
        }
        return null;
    }

    private static Kind classifySqlState(String state) {
        return switch (state) {
            case "55P03", "40P01", "40001" -> Kind.UNAVAILABLE;
            case "23505" -> Kind.DUPLICATE;
            case "23503", "23001" -> Kind.INVALID_REFERENCE;
            default -> classifySqlStatePrefix(state);
        };
    }

    private static Kind classifySqlStatePrefix(String state) {
        if (state.startsWith("22") || state.startsWith("23")) {
            return Kind.INVALID_DATA;
        }
        if (state.startsWith("08") || state.startsWith("57")) {
            return Kind.UNAVAILABLE;
        }
        return Kind.UNEXPECTED;
    }
}
