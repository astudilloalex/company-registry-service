package com.alexastudillo.companyregistry.domain.model;

public enum CompanyStatus {
    DRAFT, ACTIVE, INACTIVE, ARCHIVED;

    public boolean permitsTransitionTo(CompanyStatus target) {
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == ARCHIVED;
            case ACTIVE -> target == INACTIVE;
            case INACTIVE -> target == ACTIVE || target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public boolean terminal() {
        return this == ARCHIVED;
    }
}
