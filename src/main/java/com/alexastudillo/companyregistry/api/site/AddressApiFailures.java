package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.site.AddressFailure;
import com.alexastudillo.companyregistry.domain.text.InvalidText;

public final class AddressApiFailures {
    private AddressApiFailures() {}

    public static ApiResponseException error(String rule) {
        for (var code : AddressResponseCode.values()) if (code.getCode().equals(rule)) return new ApiResponseException(code);
        for (var code : SiteResponseCode.values()) if (code.getCode().equals(rule)) return new ApiResponseException(code);
        throw new IllegalStateException("address-error-rule-unavailable");
    }

    public static Throwable translate(Throwable failure) {
        if (failure instanceof AddressFailure address) return error(switch (address.kind()) {
            case COMPANY_NOT_FOUND -> "company-not-found";
            case SITE_NOT_FOUND -> "site-not-found";
            case ADDRESS_NOT_FOUND -> "address-not-found";
            case STALE_VERSION -> "aggregate-version-mismatch";
            case COMPANY_ARCHIVED -> "site-mutation-not-allowed-for-archived-company";
            case SITE_CLOSED -> "site-closed";
            case IDEMPOTENCY_MISMATCH -> "idempotency-key-already-used-with-different-request";
            case COUNTRY_NOT_FOUND -> "country-reference-not-found";
            case COUNTRY_INACTIVE -> "country-must-be-active";
            case DIVISION_NOT_FOUND -> "administrative-division-reference-not-found";
            case DIVISION_INACTIVE -> "administrative-division-must-be-active";
            case DEPENDENCY_INVALID -> "dependency-response-invalid";
            case UNAVAILABLE -> "dependency-unavailable";
            case CURSOR_INVALID -> "address-cursor-invalid-or-incompatible";
        });
        if (failure instanceof InvalidText text) return error(text.rule());
        return SiteApiFailures.translate(failure);
    }
}
