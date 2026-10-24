package com.alexastudillo.companyregistry;

import com.alexastudillo.companyregistry.contract.DeliveryEnvironmentResource;
import java.util.HashMap;
import java.util.Map;

/** The Quarkus-managed process stays stored-only; each scenario launches its own publisher artifact. */
public class OutboxProcessEnvironmentResource extends DeliveryEnvironmentResource {
    @Override public Map<String, String> start() {
        var values = new HashMap<>(super.start());
        values.put("company-registry.outbox.mode", "stored-only");
        settings = Map.copyOf(values);
        return values;
    }
}
