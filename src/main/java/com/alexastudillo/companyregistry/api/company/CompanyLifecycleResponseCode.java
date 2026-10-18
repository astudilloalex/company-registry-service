package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.api.response.contract.ApiResponseCode;

public enum CompanyLifecycleResponseCode implements ApiResponseCode {
    COMPANY_NOT_FOUND("company-not-found", 404),
    AGGREGATE_VERSION_MISMATCH("aggregate-version-mismatch", 412),
    COMPANY_ARCHIVED("company-archived", 409),
    COMPANY_ALREADY_IN_REQUESTED_STATE("company-already-in-requested-state", 409),
    COMPANY_TRANSITION_NOT_ALLOWED("company-transition-not-allowed", 409),
    LEGAL_HEADQUARTERS_REQUIRED("legal-headquarters-required", 409),
    ACTIVE_HEADQUARTERS_REQUIRED("active-headquarters-required", 409),
    PARTY_REFERENCE_NOT_FOUND("party-reference-not-found", 404),
    PARTY_MUST_BE_LEGAL_ENTITY("party-must-be-legal-entity", 409),
    PARTY_MUST_BE_ACTIVE("party-must-be-active", 409),
    IDEMPOTENCY_KEY_ALREADY_USED_WITH_DIFFERENT_REQUEST("idempotency-key-already-used-with-different-request", 409),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", 503),
    DEPENDENCY_RESPONSE_INVALID("dependency-response-invalid", 502),
    COMPANY_ID_INVALID("company-id-invalid", 400),
    IF_MATCH_REQUIRED("if-match-required", 400),
    IF_MATCH_DUPLICATED("if-match-duplicated", 400),
    IF_MATCH_INVALID("if-match-invalid", 400),
    IDEMPOTENCY_KEY_DUPLICATED("idempotency-key-duplicated", 400),
    IDEMPOTENCY_KEY_BLANK("idempotency-key-blank", 400),
    IDEMPOTENCY_KEY_TOO_LONG("idempotency-key-too-long", 400),
    REQUEST_BODY_INVALID_JSON("request-body-invalid-json", 400),
    REQUEST_BODY_MUST_BE_OBJECT("request-body-must-be-object", 400),
    REQUEST_BODY_CONTAINS_UNKNOWN_FIELDS("request-body-contains-unknown-fields", 400);

    private final String code;
    private final int status;
    CompanyLifecycleResponseCode(String code, int status) { this.code = code; this.status = status; }
    @Override public String getCode() { return code; }
    @Override public int getStatus() { return status; }
}
