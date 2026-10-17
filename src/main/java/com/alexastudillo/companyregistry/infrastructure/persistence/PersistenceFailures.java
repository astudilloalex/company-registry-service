package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure.Kind;
import io.vertx.pgclient.PgException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.hibernate.HibernateException;
import org.hibernate.StaleStateException;
import org.hibernate.JDBCException;

final class PersistenceFailures {
    private PersistenceFailures() {}
    static boolean technical(Throwable failure) {
        if (failure instanceof PersistenceFailure || failure instanceof java.util.concurrent.CancellationException) return false;
        return failure instanceof HibernateException || failure instanceof PersistenceException
                || failure instanceof PgException || failure instanceof ConnectException || failure instanceof TimeoutException;
    }
    static PersistenceFailure translate(Throwable failure) {
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
