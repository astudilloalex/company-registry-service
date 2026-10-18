package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.SiteStatus;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteCursorTest {
    private final UUID tenant=UUID.randomUUID();
    private final UUID company=UUID.randomUUID();
    private final SiteSelection.Filters filters=new SiteSelection.Filters(" acme ",com.alexastudillo.companyregistry.domain.model.SiteType.BRANCH,SiteStatus.CLOSED);
    private final SiteSelection.Boundary boundary=new SiteSelection.Boundary(Instant.parse("2026-10-17T14:30:00.123456Z"),UUID.randomUUID());
    private final Map<String,String> keys=Map.of("K1",Base64.getEncoder().encodeToString(new byte[32]),"K2",Base64.getEncoder().encodeToString("separate-cursor-test-material-key2".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    @Test void supportsHistoricalKeysAndExcludesLimitActorAndProcessFromScope() {
        var old=new AuthenticatedSiteCursors(new HmacKeys("K1",keys));var current=new AuthenticatedSiteCursors(new HmacKeys("K2",keys));
        var cursor=old.encode(tenant,company,filters,boundary);assertEquals(boundary,current.decode(cursor,tenant,company,filters));
        assertEquals(boundary,current.decode(cursor,tenant,company,new SiteSelection.Filters("ACME",filters.siteType(),SiteStatus.CLOSED)));
        for(int limit:new int[]{1,50,200}) assertEquals(cursor,new SiteSelection.Request(filters,limit,cursor).cursor());
        for(int limit:new int[]{0,-1,201}) assertThrows(IllegalArgumentException.class,()->new SiteSelection.Request(filters,limit,cursor));
    }
    @Test void rejectsAlteredUnknownAndIncompatibleCursorsWithoutContents() {
        var codec=new AuthenticatedSiteCursors(new HmacKeys("K1",keys));var cursor=codec.encode(tenant,company,filters,boundary);
        var parts=cursor.split("\\.");
        for(var invalid:List.of("", "invalid", parts[0]+".AAAA",cursor+".extra")) assertInvalid(()->codec.decode(invalid,tenant,company,filters));
        assertInvalid(()->codec.decode(cursor,UUID.randomUUID(),company,filters));
        assertInvalid(()->codec.decode(cursor,tenant,UUID.randomUUID(),filters));
        assertInvalid(()->codec.decode("x".repeat(4097),tenant,company,filters));
        var companyCodec = new AuthenticatedCompanyCursors(new HmacKeys("K1",keys));
        var companyFilters = new com.alexastudillo.companyregistry.application.company.CompanySelection.Filters(null, null, null);
        var companyBoundary = new com.alexastudillo.companyregistry.application.company.CompanySelection.Boundary(boundary.createdAt(),boundary.id());
        var companyCursor = companyCodec.encode(tenant,companyFilters,companyBoundary);
        assertInvalid(()->codec.decode(companyCursor,tenant,company,filters));
        assertThrows(com.alexastudillo.companyregistry.application.company.CompanyFailure.class,
                ()->companyCodec.decode(cursor,tenant,companyFilters));
        assertInvalid(()->codec.decode(cursor,tenant,company,new SiteSelection.Filters("OTHER",filters.siteType(),filters.status())));
        assertInvalid(()->new AuthenticatedSiteCursors(new HmacKeys("K2",Map.of("K2",keys.get("K2")))).decode(cursor,tenant,company,filters));
        var body=new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8).replace("\"version\":1","\"version\":2");
        var changed=Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[1];
        assertInvalid(()->codec.decode(changed,tenant,company,filters));
    }
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(SiteFailure.class,action);assertEquals(SiteFailure.Kind.CURSOR_INVALID,failure.kind());assertNull(failure.getCause());
    }
}
