package com.alexastudillo.companyregistry.api.error;

import com.alexastudillo.api.response.contract.ApiResponseCode;

public enum CompanyResponseCode implements ApiResponseCode {
    BAD_REQUEST("bad-request", 400),
    REFERENCE_NOT_FOUND("reference-not-found", 404),
    INVALID_PROCESS_ID("process-id-invalid", 400),
    INVALID_TENANT_ID("tenant-id-invalid", 400),
    INVALID_USER_ID("user-id-invalid", 400),
    INVALID_REFERENCE("reference-invalid", 400),
    INVALID_DATA("data-invalid", 400),
    CONFLICT("resource-conflict", 409),
    STALE_VERSION("aggregate-version-mismatch", 412),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", 503),
    DEPENDENCY_INVALID_RESPONSE("dependency-response-invalid", 502);

    private final String code;
    private final int status;
    CompanyResponseCode(String code, int status) { this.code = code; this.status = status; }
    @Override public String getCode() { return code; }
    @Override public int getStatus() { return status; }
}
