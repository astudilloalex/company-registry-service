package com.alexastudillo.companyregistry.application.company;

import java.util.UUID;

public interface CompanyCursors {
    String encode(UUID tenant,CompanySelection.Filters filters,CompanySelection.Boundary boundary);
    CompanySelection.Boundary decode(String cursor,UUID tenant,CompanySelection.Filters filters);
}
