package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CompanyLifecyclePolicyTest {
    @Test
    void evaluatesEveryStateAndCommandWithDistinctRejectionReasons() {
        for (var source : CompanyStatus.values()) {
            for (var target : new CompanyStatus[] {CompanyStatus.ACTIVE, CompanyStatus.INACTIVE, CompanyStatus.ARCHIVED}) {
                boolean allowed = switch (source) {
                    case DRAFT, INACTIVE -> target == CompanyStatus.ACTIVE || target == CompanyStatus.ARCHIVED;
                    case ACTIVE -> target == CompanyStatus.INACTIVE;
                    case ARCHIVED -> false;
                };
                if (allowed) {
                    assertEquals(target, CompanyLifecyclePolicy.transition(source, target));
                } else {
                    var failure = assertThrows(CompanyLifecycleRejected.class,
                            () -> CompanyLifecyclePolicy.transition(source, target));
                    var reason = source == CompanyStatus.ARCHIVED ? CompanyLifecycleRejected.Reason.COMPANY_ARCHIVED
                            : source == target ? CompanyLifecycleRejected.Reason.ALREADY_IN_REQUESTED_STATE
                            : CompanyLifecycleRejected.Reason.TRANSITION_NOT_ALLOWED;
                    assertEquals(reason, failure.reason());
                }
            }
        }
    }
}
