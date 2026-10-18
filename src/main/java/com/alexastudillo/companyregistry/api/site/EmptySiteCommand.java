package com.alexastudillo.companyregistry.api.site;

/** Absence and an explicit empty object have the same lifecycle intent. */
public record EmptySiteCommand() {
    public static final EmptySiteCommand INSTANCE = new EmptySiteCommand();
}
