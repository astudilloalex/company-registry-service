package com.alexastudillo.companyregistry.application.outbox;

/** Closed diagnostics: never retain a dependency exception or its message. */
public final class DeliveryFailure extends RuntimeException {
    public enum Code {
        INVALID_CONTRACT(true), UNSUPPORTED_EVENT(true), SERIALIZATION_FAILED(true),
        PUBLISH_TIMEOUT(false), BROKER_NACK(false), BROKER_UNAVAILABLE(false),
        DELIVERY_OUTCOME_UNKNOWN(false), ATTEMPTS_EXHAUSTED(false);
        private final boolean terminal;
        Code(boolean terminal) { this.terminal = terminal; }
        public boolean terminal() { return terminal; }
        public String diagnostic() { return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    }
    private final Code code;
    public DeliveryFailure(Code code) { super(code.diagnostic()); this.code = code; }
    public Code code() { return code; }
}
