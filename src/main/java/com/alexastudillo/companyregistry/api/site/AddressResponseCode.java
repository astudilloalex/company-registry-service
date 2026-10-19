package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.contract.ApiResponseCode;

public enum AddressResponseCode implements ApiResponseCode {
    ADDRESS_NOT_FOUND("address-not-found", 404),
    COUNTRY_REFERENCE_NOT_FOUND("country-reference-not-found", 404),
    ADMINISTRATIVE_DIVISION_REFERENCE_NOT_FOUND("administrative-division-reference-not-found", 404),
    COUNTRY_MUST_BE_ACTIVE("country-must-be-active", 409),
    ADMINISTRATIVE_DIVISION_MUST_BE_ACTIVE("administrative-division-must-be-active", 409),
    DEPENDENCY_RESPONSE_INVALID("dependency-response-invalid", 502),
    ADDRESS_TYPE_MUST_BE_STRING("address-type-must-be-string", 400),
    ADDRESS_TYPE_REQUIRED("address-type-required", 400),
    ADDRESS_TYPE_BLANK("address-type-blank", 400),
    ADDRESS_TYPE_TOO_LONG("address-type-too-long", 400),
    ADDRESS_COUNTRY_CODE_MUST_BE_STRING("address-country-code-must-be-string", 400),
    ADDRESS_COUNTRY_CODE_REQUIRED("address-country-code-required", 400),
    ADDRESS_COUNTRY_CODE_BLANK("address-country-code-blank", 400),
    ADDRESS_COUNTRY_CODE_TOO_LONG("address-country-code-too-long", 400),
    ADDRESS_LINE1_MUST_BE_STRING("address-line1-must-be-string", 400),
    ADDRESS_LINE1_REQUIRED("address-line1-required", 400),
    ADDRESS_LINE1_BLANK("address-line1-blank", 400),
    ADDRESS_LINE1_TOO_LONG("address-line1-too-long", 400),
    ADDRESS_ADMINISTRATIVE_DIVISION_CODE_MUST_BE_STRING("address-administrative-division-code-must-be-string", 400),
    ADDRESS_ADMINISTRATIVE_DIVISION_CODE_REQUIRED("address-administrative-division-code-required", 400),
    ADDRESS_ADMINISTRATIVE_DIVISION_CODE_BLANK("address-administrative-division-code-blank", 400),
    ADDRESS_ADMINISTRATIVE_DIVISION_CODE_TOO_LONG("address-administrative-division-code-too-long", 400),
    ADDRESS_LINE2_MUST_BE_STRING("address-line2-must-be-string", 400),
    ADDRESS_LINE2_REQUIRED("address-line2-required", 400),
    ADDRESS_LINE2_BLANK("address-line2-blank", 400),
    ADDRESS_LINE2_TOO_LONG("address-line2-too-long", 400),
    ADDRESS_HOUSE_NUMBER_MUST_BE_STRING("address-house-number-must-be-string", 400),
    ADDRESS_HOUSE_NUMBER_REQUIRED("address-house-number-required", 400),
    ADDRESS_HOUSE_NUMBER_BLANK("address-house-number-blank", 400),
    ADDRESS_HOUSE_NUMBER_TOO_LONG("address-house-number-too-long", 400),
    ADDRESS_POSTAL_CODE_MUST_BE_STRING("address-postal-code-must-be-string", 400),
    ADDRESS_POSTAL_CODE_REQUIRED("address-postal-code-required", 400),
    ADDRESS_POSTAL_CODE_BLANK("address-postal-code-blank", 400),
    ADDRESS_POSTAL_CODE_TOO_LONG("address-postal-code-too-long", 400),
    ADDRESS_TYPE_NOT_SUPPORTED("address-type-not-supported", 400),
    ADDRESS_COUNTRY_CODE_INVALID("address-country-code-invalid", 400),
    ADDRESS_IS_PRIMARY_MUST_BE_BOOLEAN("address-is-primary-must-be-boolean", 400),
    ADDRESS_IS_PRIMARY_FILTER_INVALID("address-is-primary-filter-invalid", 400),
    ADDRESS_ID_INVALID("address-id-invalid", 400),
    ADDRESS_COORDINATES_MUST_BE_PROVIDED_TOGETHER("address-coordinates-must-be-provided-together", 400),
    ADDRESS_COORDINATES_MUST_BE_BOTH_NULL_OR_NUMERIC("address-coordinates-must-be-both-null-or-numeric", 400),
    ADDRESS_PAGE_LIMIT_MUST_BE_AN_INTEGER("address-page-limit-must-be-an-integer", 400),
    ADDRESS_PAGE_LIMIT_MUST_BE_BETWEEN_1_AND_200("address-page-limit-must-be-between-1-and-200", 400),
    ADDRESS_CURSOR_INVALID_OR_INCOMPATIBLE("address-cursor-invalid-or-incompatible", 400),
    ADDRESS_LATITUDE_MUST_BE_NUMBER("address-latitude-must-be-number", 400),
    ADDRESS_LATITUDE_OUT_OF_RANGE("address-latitude-out-of-range", 400),
    ADDRESS_LATITUDE_PRECISION_EXCEEDED("address-latitude-precision-exceeded", 400),
    ADDRESS_LONGITUDE_MUST_BE_NUMBER("address-longitude-must-be-number", 400),
    ADDRESS_LONGITUDE_OUT_OF_RANGE("address-longitude-out-of-range", 400),
    ADDRESS_LONGITUDE_PRECISION_EXCEEDED("address-longitude-precision-exceeded", 400);
    private final String code;
    private final int status;
    AddressResponseCode(String code, int status) { this.code = code; this.status = status; }
    @Override public String getCode() { return code; }
    @Override public int getStatus() { return status; }
}
