package com.alexastudillo.companyregistry.application.reference;

/** Neutral dependency failure, distinct from absence, type incompatibility and inactivity. */
public final class ReferenceFailure extends RuntimeException {
    public enum Kind { TIMEOUT, UNAVAILABLE, INVALID_CONTRACT }
    private final Kind kind;
    public ReferenceFailure(Kind kind) {
        super("reference-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        this.kind = java.util.Objects.requireNonNull(kind);
    }
    public Kind kind() { return kind; }
}
