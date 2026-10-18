package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;

/** Share one provider/database application across both HU03 HTTP suites. */
public class LifecycleHttpProfile implements QuarkusTestProfile {
    @Override public boolean disableGlobalTestResources() { return true; }
    @Override public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresResource.class), new TestResourceEntry(LifecycleReferenceStubResource.class));
    }
}
