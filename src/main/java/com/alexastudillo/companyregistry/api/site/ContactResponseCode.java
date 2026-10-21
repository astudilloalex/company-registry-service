package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.contract.ApiResponseCode;

public enum ContactResponseCode implements ApiResponseCode {
    CONTACT_NOT_FOUND("contact-not-found", 404),
    CONTACT_VALUE_ALREADY_EXISTS("contact-value-already-exists", 409),
    CONTACT_TYPE_MUST_BE_STRING("contact-type-must-be-string", 400),
    CONTACT_TYPE_REQUIRED("contact-type-required", 400),
    CONTACT_TYPE_NOT_SUPPORTED("contact-type-not-supported", 400),
    CONTACT_VALUE_MUST_BE_STRING("contact-value-must-be-string", 400),
    CONTACT_VALUE_REQUIRED("contact-value-required", 400),
    CONTACT_VALUE_INVALID("contact-value-invalid", 400),
    CONTACT_VALUE_TOO_LONG("contact-value-too-long", 400),
    CONTACT_MOBILE_NUMBER_REQUIRED("contact-mobile-number-required", 400),
    CONTACT_LABEL_MUST_BE_STRING("contact-label-must-be-string", 400),
    CONTACT_LABEL_BLANK("contact-label-blank", 400),
    CONTACT_LABEL_TOO_LONG("contact-label-too-long", 400),
    CONTACT_IS_PRIMARY_MUST_BE_BOOLEAN("contact-is-primary-must-be-boolean", 400),
    CONTACT_IS_PRIMARY_FILTER_INVALID("contact-is-primary-filter-invalid", 400),
    CONTACT_ID_INVALID("contact-id-invalid", 400),
    CONTACT_PAGE_LIMIT_MUST_BE_AN_INTEGER("contact-page-limit-must-be-an-integer", 400),
    CONTACT_PAGE_LIMIT_MUST_BE_BETWEEN_1_AND_200("contact-page-limit-must-be-between-1-and-200", 400),
    CONTACT_CURSOR_INVALID_OR_INCOMPATIBLE("contact-cursor-invalid-or-incompatible", 400);
    private final String code;
    private final int status;
    ContactResponseCode(String code, int status) { this.code = code; this.status = status; }
    @Override public String getCode() { return code; }
    @Override public int getStatus() { return status; }
}
