package com.alexastudillo.companyregistry.application.site;

public interface AddressCursors {
    String encode(AddressScope scope, AddressSelection.Filters filters, AddressSelection.Boundary boundary);
    AddressSelection.Boundary decode(String cursor, AddressScope scope, AddressSelection.Filters filters);
}
