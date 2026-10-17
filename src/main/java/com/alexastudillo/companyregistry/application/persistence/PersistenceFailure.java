package com.alexastudillo.companyregistry.application.persistence;

/** Transport-neutral classification. Causes are diagnostic only and must never become public messages. */
public final class PersistenceFailure extends RuntimeException {
    public enum Kind { DUPLICATE, INVALID_REFERENCE, INVALID_DATA, STALE_VERSION, UNAVAILABLE, UNEXPECTED }
    private final Kind kind;

    public PersistenceFailure(Kind kind, Throwable cause) {
        super("persistence-" + kind.name().toLowerCase(java.util.Locale.ROOT), cause);
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
