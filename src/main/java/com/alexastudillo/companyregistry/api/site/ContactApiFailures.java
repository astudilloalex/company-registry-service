package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.site.ContactFailure;
import com.alexastudillo.companyregistry.domain.text.InvalidText;

public final class ContactApiFailures {
    private ContactApiFailures() {}

    public static ApiResponseException error(String rule) {
        for (var code : ContactResponseCode.values()) if (code.getCode().equals(rule)) return new ApiResponseException(code);
        for (var code : SiteResponseCode.values()) if (code.getCode().equals(rule)) return new ApiResponseException(code);
        throw new IllegalStateException("contact-error-rule-unavailable");
    }

    public static Throwable translate(Throwable failure) {
        if (failure instanceof ContactFailure contact) return error(switch (contact.kind()) {
            case COMPANY_NOT_FOUND -> "company-not-found";
            case SITE_NOT_FOUND -> "site-not-found";
            case CONTACT_NOT_FOUND -> "contact-not-found";
            case STALE_VERSION -> "aggregate-version-mismatch";
            case COMPANY_ARCHIVED -> "site-mutation-not-allowed-for-archived-company";
            case SITE_CLOSED -> "site-closed";
            case IDEMPOTENCY_MISMATCH -> "idempotency-key-already-used-with-different-request";
            case CONTACT_VALUE -> "contact-value-already-exists";
            case UNAVAILABLE -> "dependency-unavailable";
            case CURSOR_INVALID -> "contact-cursor-invalid-or-incompatible";
        });
        if (failure instanceof InvalidText text) return error(text.rule());
        return SiteApiFailures.translate(failure);
    }
}
