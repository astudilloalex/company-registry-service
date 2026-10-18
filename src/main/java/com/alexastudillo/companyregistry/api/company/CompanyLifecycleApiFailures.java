package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.company.CompanyLifecycleFailure;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;

public final class CompanyLifecycleApiFailures {
    private CompanyLifecycleApiFailures() {}
    public static ApiResponseException error(CompanyLifecycleResponseCode code) { return new ApiResponseException(code); }

    public static Throwable translate(Throwable failure) {
        if (failure instanceof CompanyLifecycleFailure lifecycle) return error(switch (lifecycle.kind()) {
            case COMPANY_NOT_FOUND -> CompanyLifecycleResponseCode.COMPANY_NOT_FOUND;
            case STALE_VERSION -> CompanyLifecycleResponseCode.AGGREGATE_VERSION_MISMATCH;
            case COMPANY_ARCHIVED -> CompanyLifecycleResponseCode.COMPANY_ARCHIVED;
            case ALREADY_IN_REQUESTED_STATE -> CompanyLifecycleResponseCode.COMPANY_ALREADY_IN_REQUESTED_STATE;
            case TRANSITION_NOT_ALLOWED -> CompanyLifecycleResponseCode.COMPANY_TRANSITION_NOT_ALLOWED;
            case LEGAL_HEADQUARTERS_REQUIRED -> CompanyLifecycleResponseCode.LEGAL_HEADQUARTERS_REQUIRED;
            case ACTIVE_HEADQUARTERS_REQUIRED -> CompanyLifecycleResponseCode.ACTIVE_HEADQUARTERS_REQUIRED;
            case PARTY_NOT_FOUND -> CompanyLifecycleResponseCode.PARTY_REFERENCE_NOT_FOUND;
            case PARTY_MUST_BE_LEGAL_ENTITY -> CompanyLifecycleResponseCode.PARTY_MUST_BE_LEGAL_ENTITY;
            case PARTY_MUST_BE_ACTIVE -> CompanyLifecycleResponseCode.PARTY_MUST_BE_ACTIVE;
            case IDEMPOTENCY_MISMATCH -> CompanyLifecycleResponseCode.IDEMPOTENCY_KEY_ALREADY_USED_WITH_DIFFERENT_REQUEST;
            case UNAVAILABLE -> CompanyLifecycleResponseCode.DEPENDENCY_UNAVAILABLE;
        });
        if (failure instanceof ReferenceFailure reference) return error(reference.kind() == ReferenceFailure.Kind.INVALID_CONTRACT
                ? CompanyLifecycleResponseCode.DEPENDENCY_RESPONSE_INVALID : CompanyLifecycleResponseCode.DEPENDENCY_UNAVAILABLE);
        if (failure instanceof PersistenceFailure persistence && persistence.kind() == PersistenceFailure.Kind.UNAVAILABLE) {
            return error(CompanyLifecycleResponseCode.DEPENDENCY_UNAVAILABLE);
        }
        return failure;
    }
}
