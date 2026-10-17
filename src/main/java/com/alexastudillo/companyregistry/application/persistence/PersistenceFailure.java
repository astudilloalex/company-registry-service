package com.alexastudillo.companyregistry.application.persistence;

/** Transport-neutral classification. Causes are diagnostic only and must never become public messages. */
public final class PersistenceFailure extends RuntimeException {
    public enum Kind { DUPLICATE, INVALID_REFERENCE, INVALID_DATA, STALE_VERSION, UNAVAILABLE, UNEXPECTED }
    public enum Uniqueness { COMPANY_CODE, COMPANY_PARTY, COMPLETION_KEY, OTHER }
    private final Kind kind;
    private final Uniqueness uniqueness;

    public PersistenceFailure(Kind kind, Throwable cause) {
        this(kind, Uniqueness.OTHER, cause);
    }
    public PersistenceFailure(Kind kind, Uniqueness uniqueness, Throwable cause) {
        super("persistence-" + kind.name().toLowerCase(java.util.Locale.ROOT), cause);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.uniqueness = java.util.Objects.requireNonNull(uniqueness);
    }

    public Kind kind() { return kind; }
    public Uniqueness uniqueness() { return uniqueness; }
}
