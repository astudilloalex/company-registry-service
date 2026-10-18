package com.alexastudillo.companyregistry.api.company;

/** Absence and an explicit empty object have the same lifecycle intent. */
public record EmptyCompanyCommand() {
    public static final EmptyCompanyCommand INSTANCE = new EmptyCompanyCommand();
}
