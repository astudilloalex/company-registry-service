package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import java.util.UUID;

/** Preserve structured text and presence for versioned acceptance comparison. */
public record CreateSiteRequest(String code, String name, FieldChange siteType) {
    public SiteRegistration.Command command(OperationContext context, String key, UUID companyId) {
        return new SiteRegistration.Command(context, key, companyId, code, name, siteType);
    }
}
