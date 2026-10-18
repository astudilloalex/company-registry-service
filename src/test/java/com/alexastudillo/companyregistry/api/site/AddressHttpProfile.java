package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;

/**
 * Same isolated provider/database composition for all ordinary HU06 HTTP
 * suites.
 */
public class AddressHttpProfile implements QuarkusTestProfile {
    @Override
    public boolean disableGlobalTestResources() {
        return true;
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresResource.class),
                new TestResourceEntry(AddressReferenceResource.class));
    }
}
