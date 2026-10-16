package com.alexastudillo.companyregistry.domain.model;

public enum SiteStatus {
    DRAFT, ACTIVE, INACTIVE, CLOSED;

    public boolean permitsTransitionTo(SiteStatus target) {
        return switch (this) {
            case DRAFT, INACTIVE -> target == ACTIVE || target == CLOSED;
            case ACTIVE -> target == INACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }

    public boolean terminal() {
        return this == CLOSED;
    }
}
