package com.alexastudillo.companyregistry.domain.model;

/** Operational values only; identity, lifecycle and audit cannot be patched. */
public record CompanyInformation(String code, String operatingName, String description, String logoUrl) {
}
