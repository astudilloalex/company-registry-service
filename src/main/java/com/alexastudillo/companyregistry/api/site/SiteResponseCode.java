package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.contract.ApiResponseCode;

public enum SiteResponseCode implements ApiResponseCode {
    CREATED("successful", 201),
    COMPANY_NOT_FOUND("company-not-found", 404),
    SITE_NOT_FOUND("site-not-found", 404),
    SITE_CODE_ALREADY_EXISTS("site-code-already-exists", 409),
    HEADQUARTERS_ALREADY_EXISTS("headquarters-already-exists", 409),
    IDEMPOTENCY_KEY_ALREADY_USED_WITH_DIFFERENT_REQUEST("idempotency-key-already-used-with-different-request", 409),
    SITE_REGISTRATION_NOT_ALLOWED_FOR_ARCHIVED_COMPANY("site-registration-not-allowed-for-archived-company", 409),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", 503),
    SITE_CODE_REQUIRED("site-code-required", 400),
    SITE_CODE_TOO_LONG("site-code-too-long", 400),
    SITE_NAME_REQUIRED("site-name-required", 400),
    SITE_NAME_TOO_LONG("site-name-too-long", 400),
    SITE_TYPE_NULL_NOT_ALLOWED("site-type-null-not-allowed", 400),
    SITE_TYPE_NOT_SUPPORTED("site-type-not-supported", 400),
    SITE_CODE_MUST_BE_STRING("site-code-must-be-string", 400),
    SITE_NAME_MUST_BE_STRING("site-name-must-be-string", 400),
    SITE_TYPE_MUST_BE_STRING("site-type-must-be-string", 400),
    REQUEST_BODY_REQUIRED("request-body-required", 400),
    REQUEST_BODY_INVALID_JSON("request-body-invalid-json", 400),
    REQUEST_BODY_MUST_BE_OBJECT("request-body-must-be-object", 400),
    REQUEST_BODY_CONTAINS_UNKNOWN_FIELDS("request-body-contains-unknown-fields", 400),
    REQUEST_BODY_CONTAINS_DUPLICATE_FIELDS("request-body-contains-duplicate-fields", 400),
    COMPANY_ID_INVALID("company-id-invalid", 400),
    SITE_ID_INVALID("site-id-invalid", 400),
    IDEMPOTENCY_KEY_REQUIRED("idempotency-key-required", 400),
    IDEMPOTENCY_KEY_DUPLICATED("idempotency-key-duplicated", 400),
    IDEMPOTENCY_KEY_BLANK("idempotency-key-blank", 400),
    IDEMPOTENCY_KEY_TOO_LONG("idempotency-key-too-long", 400),
    QUERY_PARAMETER_NOT_SUPPORTED("query-parameter-not-supported", 400),
    QUERY_PARAMETER_REPEATED("query-parameter-repeated", 400),
    SITE_STATUS_NOT_SUPPORTED("site-status-not-supported", 400),
    SITE_PAGE_LIMIT_MUST_BE_AN_INTEGER("site-page-limit-must-be-an-integer", 400),
    SITE_PAGE_LIMIT_MUST_BE_BETWEEN_1_AND_200("site-page-limit-must-be-between-1-and-200", 400),
    SITE_CURSOR_INVALID_OR_INCOMPATIBLE("site-cursor-invalid-or-incompatible", 400);
    private final String code;
    private final int status;
    SiteResponseCode(String code, int status) { this.code = code; this.status = status; }
    @Override public String getCode() { return code; }
    @Override public int getStatus() { return status; }
}
