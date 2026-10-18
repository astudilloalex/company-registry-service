package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.site.SiteFailure;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.domain.text.InvalidText;

public final class SiteApiFailures {
    private SiteApiFailures() {}
    public static ApiResponseException error(SiteResponseCode code) { return new ApiResponseException(code); }
    public static Throwable translate(Throwable failure) {
        if (failure instanceof SiteFailure site) return error(switch (site.kind()) {
            case COMPANY_NOT_FOUND -> SiteResponseCode.COMPANY_NOT_FOUND;
            case SITE_NOT_FOUND -> SiteResponseCode.SITE_NOT_FOUND;
            case COMPANY_ARCHIVED -> SiteResponseCode.SITE_REGISTRATION_NOT_ALLOWED_FOR_ARCHIVED_COMPANY;
            case CODE_OCCUPIED -> SiteResponseCode.SITE_CODE_ALREADY_EXISTS;
            case HEADQUARTERS_OCCUPIED -> SiteResponseCode.HEADQUARTERS_ALREADY_EXISTS;
            case IDEMPOTENCY_MISMATCH -> SiteResponseCode.IDEMPOTENCY_KEY_ALREADY_USED_WITH_DIFFERENT_REQUEST;
            case CURSOR_INVALID -> SiteResponseCode.SITE_CURSOR_INVALID_OR_INCOMPATIBLE;
            case UNAVAILABLE -> SiteResponseCode.DEPENDENCY_UNAVAILABLE;
        });
        if (failure instanceof InvalidText text) {
            for (var code : SiteResponseCode.values()) if (code.getStatus() == 400 && code.getCode().equals(text.rule())) return error(code);
        }
        if (failure instanceof PersistenceFailure persistence && persistence.kind() == PersistenceFailure.Kind.UNAVAILABLE
                || failure instanceof com.alexastudillo.companyregistry.application.company.CompanyFailure company
                    && company.kind() == com.alexastudillo.companyregistry.application.company.CompanyFailure.Kind.UNAVAILABLE)
            return error(SiteResponseCode.DEPENDENCY_UNAVAILABLE);
        return failure;
    }
}
