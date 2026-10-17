package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.text.BusinessText;
import java.time.Instant;
import java.util.UUID;

public final class CompanySelection {
    private CompanySelection() {}
    public record Filters(String code, UUID partyId, CompanyStatus status) {
        public Filters { code=code==null?null:BusinessText.required(code,64); }
    }
    public record Boundary(Instant createdAt,UUID id) {}
    public record Request(Filters filters,int limit,String cursor) {
        public Request { if(limit<1 || limit>200) throw new IllegalArgumentException("limit-invalid"); }
    }
}
