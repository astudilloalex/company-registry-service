package com.alexastudillo.companyregistry.api.site;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ContactBudgetHttpTest.Profile.class)
class ContactBudgetHttpTest extends ContactHttpSupport {
    public static class Profile extends ContactHttpProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("company-registry.site-contact.wait-budget", "2s", "company-registry.outbox.mode",
                    "stored-only");
        }
    }

    @Test
    void lockTimeoutLeavesNoAcceptanceAndNeverRetriesTheMutation() throws Exception {
        var f = fixture();
        var id = seedContact(f, "EMAIL", false);
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

}
