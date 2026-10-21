package com.alexastudillo.companyregistry.application.site;

public interface ContactCursors {
    String encode(ContactScope scope, ContactSelection.Filters filters, ContactSelection.Boundary boundary);
    ContactSelection.Boundary decode(String cursor, ContactScope scope, ContactSelection.Filters filters);
}
