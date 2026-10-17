package com.alexastudillo.companyregistry.application.company;

public final class CompanyFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, REFERENCE_NOT_FOUND, INVALID_REFERENCE, CONFLICT, INVALID_INPUT, VERSION_MISMATCH, UNAVAILABLE }
    private final Kind kind;
    public CompanyFailure(Kind kind) { super("company-"+kind.name().toLowerCase(java.util.Locale.ROOT)); this.kind=kind; }
    public Kind kind() { return kind; }
}
