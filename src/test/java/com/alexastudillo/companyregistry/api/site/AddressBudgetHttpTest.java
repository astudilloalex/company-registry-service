package com.alexastudillo.companyregistry.api.site;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.junit.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AddressBudgetHttpTest.Profile.class)
class AddressBudgetHttpTest extends AddressHttpSupport {
    public static class Profile extends AddressHttpProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("company-registry.site-address.wait-budget", "2s");
        }
    }

    @Test
    void lockTimeoutLeavesNoAcceptanceAndNeverRetriesTheMutation() throws Exception {
        var f = fixture();
        var id = seedAddress(f, "PHYSICAL", false);
        try (var c = db(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
            long start = System.nanoTime();
            mutation(0, "lock").body("{\"isPrimary\":true}").patch(f.path() + "/" + id).then().statusCode(503)
                    .body("code", equalTo("dependency-unavailable"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
            c.rollback();
        }
        assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(0, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
        assertEquals(0, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
    }

    @Test
    void totalDeadlineBoundsRequiredHttpAndTheUnconsumedKeyCanBeUsedLater() throws Exception {
        var f = fixture();
        var stub = AddressReferenceResource.stub;
        stub.reset();
        var path = "/api/v1/countries/by-alpha2/EC";
        stub.reply(path, new ReferenceStubServer.Reply(200, "{}", 5000));
        var body = "{\"addressType\":\"PHYSICAL\",\"countryCode\":\"EC\",\"addressLine1\":\"A\"}";
        long start = System.nanoTime();
        mutation(0, "retry").body(body).post(f.path()).then().statusCode(503).body("code",
                equalTo("dependency-unavailable"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertEquals(0, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site() + "'"));
        assertEquals(0, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
        country("EC", UUID.randomUUID(), "ACTIVE");
        mutation(0, "retry").body(body).post(f.path()).then().statusCode(201).body("data.siteVersion", equalTo(1));
    }
}
