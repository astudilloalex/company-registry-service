package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SiteLifecyclePolicyTest {
    @Test
    void evaluatesAllSixteenTransitionsUnderEveryEditableParent() {
        for (var parent : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE}) {
            for (var source : SiteStatus.values()) {
                for (var target : SiteStatus.values()) {
                    boolean allowed = switch (source) {
                        case DRAFT, INACTIVE -> target == SiteStatus.ACTIVE || target == SiteStatus.CLOSED;
                        case ACTIVE -> target == SiteStatus.INACTIVE || target == SiteStatus.CLOSED;
                        case CLOSED -> false;
                    };
                    if (allowed) {
                        assertEquals(target, SiteLifecyclePolicy.transition(parent, SiteType.BRANCH, source, target));
                    } else {
                        var failure = assertThrows(SiteMutationRejected.class,
                                () -> SiteLifecyclePolicy.transition(parent, SiteType.BRANCH, source, target));
                        var expected = source == SiteStatus.CLOSED ? SiteMutationRejected.Reason.SITE_CLOSED
                                : source == target ? SiteMutationRejected.Reason.ALREADY_IN_REQUESTED_STATE
                                : SiteMutationRejected.Reason.TRANSITION_NOT_ALLOWED;
                        assertEquals(expected, failure.reason());
                    }
                }
            }
        }
    }

    @Test
    void archivedPrecedesClosedOrCommandEligibilityForEveryType() {
        for (var type : SiteType.values()) {
            for (var source : SiteStatus.values()) {
                for (var target : SiteStatus.values()) {
                    var failure = assertThrows(SiteMutationRejected.class,
                            () -> SiteLifecyclePolicy.transition(CompanyStatus.ARCHIVED, type, source, target));
                    assertEquals(SiteMutationRejected.Reason.COMPANY_ARCHIVED, failure.reason());
                }
                var failure = assertThrows(SiteMutationRejected.class,
                        () -> SiteLifecyclePolicy.requireEditable(CompanyStatus.ARCHIVED, source));
                assertEquals(SiteMutationRejected.Reason.COMPANY_ARCHIVED, failure.reason());
            }
        }
        var closed = assertThrows(SiteMutationRejected.class,
                () -> SiteLifecyclePolicy.requireEditable(CompanyStatus.DRAFT, SiteStatus.CLOSED));
        assertEquals(SiteMutationRejected.Reason.SITE_CLOSED, closed.reason());
    }

    @Test
    void protectsOnlyTheActiveLegalHeadquartersOfAnActiveParent() {
        for (var parent : CompanyStatus.values()) {
            for (var previousType : SiteType.values()) {
                for (var previousStatus : SiteStatus.values()) {
                    for (var resultingType : SiteType.values()) {
                        for (var resultingStatus : SiteStatus.values()) {
                            boolean protectedRemoval = parent == CompanyStatus.ACTIVE
                                    && previousType == SiteType.HEADQUARTERS && previousStatus == SiteStatus.ACTIVE
                                    && (resultingType != SiteType.HEADQUARTERS || resultingStatus != SiteStatus.ACTIVE);
                            if (protectedRemoval) {
                                var failure = assertThrows(SiteMutationRejected.class,
                                        () -> SiteLifecyclePolicy.requireHeadquarters(parent, previousType, previousStatus,
                                                resultingType, resultingStatus));
                                assertEquals(SiteMutationRejected.Reason.ACTIVE_HEADQUARTERS_REQUIRED, failure.reason());
                            } else {
                                assertDoesNotThrow(() -> SiteLifecyclePolicy.requireHeadquarters(parent, previousType,
                                        previousStatus, resultingType, resultingStatus));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void permitsPreparationButRejectsClosingOrDeactivatingAnActiveLegalHeadquarters() {
        for (var parent : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.INACTIVE}) {
            for (var source : new SiteStatus[] {SiteStatus.DRAFT, SiteStatus.INACTIVE}) {
                assertEquals(SiteStatus.ACTIVE,
                        SiteLifecyclePolicy.transition(parent, SiteType.HEADQUARTERS, source, SiteStatus.ACTIVE));
            }
            assertEquals(SiteStatus.CLOSED, SiteLifecyclePolicy.transition(parent, SiteType.HEADQUARTERS,
                    SiteStatus.ACTIVE, SiteStatus.CLOSED));
        }
        for (var target : new SiteStatus[] {SiteStatus.CLOSED, SiteStatus.INACTIVE}) {
            var failure = assertThrows(SiteMutationRejected.class, () -> SiteLifecyclePolicy.transition(
                    CompanyStatus.ACTIVE, SiteType.HEADQUARTERS, SiteStatus.ACTIVE, target));
            assertEquals(SiteMutationRejected.Reason.ACTIVE_HEADQUARTERS_REQUIRED, failure.reason());
        }
    }
}
