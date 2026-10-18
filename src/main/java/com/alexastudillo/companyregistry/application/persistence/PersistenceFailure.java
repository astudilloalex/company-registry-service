package com.alexastudillo.companyregistry.application.persistence;

/** Transport-neutral classification. Causes are diagnostic only and must never become public messages. */
public final class PersistenceFailure extends RuntimeException {
    public enum Kind { DUPLICATE, INVALID_REFERENCE, INVALID_DATA, STALE_VERSION, UNAVAILABLE, UNEXPECTED }
    public enum Uniqueness { COMPANY_CODE, COMPANY_PARTY, SITE_CODE, SITE_HEADQUARTERS, COMPLETION_KEY, OTHER }
    private final Kind kind;
    private final Uniqueness uniqueness;
    private final boolean transactionFinalized;

    public PersistenceFailure(Kind kind, Throwable cause) {
        this(kind, Uniqueness.OTHER, cause);
    }
    public PersistenceFailure(Kind kind, Uniqueness uniqueness, Throwable cause) {
        this(kind, uniqueness, cause, true);
    }
    private PersistenceFailure(Kind kind, Uniqueness uniqueness, Throwable cause, boolean transactionFinalized) {
        super("persistence-" + kind.name().toLowerCase(java.util.Locale.ROOT), cause);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.uniqueness = java.util.Objects.requireNonNull(uniqueness);
        this.transactionFinalized = transactionFinalized;
    }

    /** A deadline can end the wait before rollback/close has been acknowledged. */
    public static PersistenceFailure finalizationUncertain() {
        return new PersistenceFailure(Kind.UNAVAILABLE, Uniqueness.OTHER, null, false);
    }

    public Kind kind() { return kind; }
    public Uniqueness uniqueness() { return uniqueness; }
    public boolean transactionFinalized() { return transactionFinalized; }
}
