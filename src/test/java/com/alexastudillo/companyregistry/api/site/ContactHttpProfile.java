package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;

/**
 * Same isolated provider/database composition for all ordinary HU07 HTTP
 * suites.
 */
public class ContactHttpProfile implements QuarkusTestProfile {
    @Override
    public java.util.Map<String, String> getConfigOverrides() {
        return java.util.Map.of("company-registry.outbox.mode", "stored-only");
    }

    @Override
    public boolean disableGlobalTestResources() {
        return true;
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresResource.class));
    }
}
