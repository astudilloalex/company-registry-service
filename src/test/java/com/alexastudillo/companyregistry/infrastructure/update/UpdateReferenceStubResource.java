package com.alexastudillo.companyregistry.infrastructure.update;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** No provider response is configured; any accidental call is observable and unusable. */
public class UpdateReferenceStubResource implements QuarkusTestResourceLifecycleManager {
    static ReferenceStubServer stub;
    @Override public Map<String,String> start() {
        try {stub=new ReferenceStubServer();}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        return Map.of("quarkus.rest-client.party-registry.url",stub.url(),"quarkus.rest-client.geographic-reference.url",stub.url());
    }
    @Override public void stop(){if(stub!=null)stub.close();}
}
