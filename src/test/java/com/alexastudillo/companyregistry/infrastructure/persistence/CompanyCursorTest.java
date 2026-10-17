package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompanyCursorTest {
    private final UUID tenant=UUID.randomUUID();
    private final CompanySelection.Filters filters=new CompanySelection.Filters(" acme ",UUID.randomUUID(),CompanyStatus.ARCHIVED);
    private final CompanySelection.Boundary boundary=new CompanySelection.Boundary(Instant.parse("2026-10-17T14:30:00.123456Z"),UUID.randomUUID());
    private final Map<String,String> keys=Map.of("K1",Base64.getEncoder().encodeToString(new byte[32]),"K2",Base64.getEncoder().encodeToString("separate-cursor-test-material-key2".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    @Test void supportsHistoricalKeysAndExcludesLimitActorAndProcessFromScope() {
        var old=new AuthenticatedCompanyCursors(new HmacKeys("K1",keys));var current=new AuthenticatedCompanyCursors(new HmacKeys("K2",keys));
        var cursor=old.encode(tenant,filters,boundary);assertEquals(boundary,current.decode(cursor,tenant,filters));
        assertEquals(boundary,current.decode(cursor,tenant,new CompanySelection.Filters("ACME",filters.partyId(),CompanyStatus.ARCHIVED)));
        for(int limit:new int[]{1,50,200}) assertEquals(cursor,new CompanySelection.Request(filters,limit,cursor).cursor());
        for(int limit:new int[]{0,-1,201}) assertThrows(IllegalArgumentException.class,()->new CompanySelection.Request(filters,limit,cursor));
    }
    @Test void rejectsAlteredUnknownAndIncompatibleCursorsWithoutContents() {
        var codec=new AuthenticatedCompanyCursors(new HmacKeys("K1",keys));var cursor=codec.encode(tenant,filters,boundary);
        var parts=cursor.split("\\.");
        for(var invalid:List.of("", "invalid", parts[0]+".AAAA",cursor+".extra")) assertInvalid(()->codec.decode(invalid,tenant,filters));
        assertInvalid(()->codec.decode(cursor,UUID.randomUUID(),filters));
        assertInvalid(()->codec.decode(cursor,tenant,new CompanySelection.Filters("OTHER",filters.partyId(),filters.status())));
        assertInvalid(()->new AuthenticatedCompanyCursors(new HmacKeys("K2",Map.of("K2",keys.get("K2")))).decode(cursor,tenant,filters));
        var body=new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8).replace("\"version\":1","\"version\":2");
        var changed=Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[1];
        assertInvalid(()->codec.decode(changed,tenant,filters));
    }
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(CompanyFailure.class,action);assertEquals(CompanyFailure.Kind.INVALID_INPUT,failure.kind());assertNull(failure.getCause());
    }
}
