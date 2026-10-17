package com.alexastudillo.companyregistry.infrastructure.outbox;

/** Storage policy only; published reserves future HU08 delivery without activating a publisher. */
public enum OutboxMode {
    DISABLED, STORED_ONLY, PUBLISHED;
    public static OutboxMode parse(String value) {
        return switch (value) {
            case "disabled" -> DISABLED;
            case "stored-only" -> STORED_ONLY;
            case "published" -> PUBLISHED;
            default -> throw new IllegalArgumentException("outbox-mode-invalid");
        };
    }
}
