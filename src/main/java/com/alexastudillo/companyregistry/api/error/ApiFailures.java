package com.alexastudillo.companyregistry.api.error;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;

/** Known neutral failures are translated here; the shared mapper owns final HTTP errors. */
public final class ApiFailures {
    private ApiFailures() {}
    public static Throwable translate(Throwable failure) {
        if (failure instanceof com.alexastudillo.companyregistry.application.reference.ReferenceFailure reference) {
            return new ApiResponseException(reference.kind() == com.alexastudillo.companyregistry.application.reference.ReferenceFailure.Kind.INVALID_CONTRACT
                    ? CompanyResponseCode.DEPENDENCY_INVALID_RESPONSE : CompanyResponseCode.DEPENDENCY_UNAVAILABLE);
        }
        if (failure instanceof PersistenceFailure persistence) {
            var code = switch (persistence.kind()) {
                case DUPLICATE -> CompanyResponseCode.CONFLICT;
                case INVALID_REFERENCE -> CompanyResponseCode.INVALID_REFERENCE;
                case INVALID_DATA -> CompanyResponseCode.INVALID_DATA;
                case STALE_VERSION -> CompanyResponseCode.STALE_VERSION;
                case UNAVAILABLE -> CompanyResponseCode.DEPENDENCY_UNAVAILABLE;
                case UNEXPECTED -> null;
            };
            if (code != null) return new ApiResponseException(code, failure);
        }
        return failure;
    }
}
