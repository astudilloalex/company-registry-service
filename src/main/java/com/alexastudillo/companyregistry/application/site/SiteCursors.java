package com.alexastudillo.companyregistry.application.site;

import java.util.UUID;

public interface SiteCursors {
    String encode(UUID tenant,UUID company,SiteSelection.Filters filters,SiteSelection.Boundary boundary);
    SiteSelection.Boundary decode(String cursor,UUID tenant,UUID company,SiteSelection.Filters filters);
}
