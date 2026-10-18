package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.site.SiteMutationFailure;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteMutationBoundaryTest {
    @Test void readerPreservesRawStructuredInputAndPresenceWithoutDestructiveCanonicalization() {
        var reader=new UpdateSiteRequestReader();
        var request=read(reader,"{\"name\":\" compañía  ágil \",\"code\":\" ß \"}");
        assertEquals(" ß ",request.patch().code().value());assertEquals(" compañía  ágil ",request.patch().name().value());
        assertEquals(FieldChange.absent(),request.patch().siteType());
        var explicit=read(reader,"{\"code\":\"x\",\"name\":\"y\",\"siteType\":\"BRANCH\"}");
        assertEquals(FieldChange.present("BRANCH"),explicit.patch().siteType());
    }
    @Test void readerChecksFullStructureAndEarlierFieldsBeforeLaterFieldDiagnostics() {
        var reader=new UpdateSiteRequestReader();
        for(var input:List.of("{\"code\":1,\"extra\":false}","{\"code\":1,\"code\":2}",
                "{\"name\":false,\"code\":null}","{\"code\":\"x\",\"siteType\":false}",
                "{\"code\":\"x\",\"name\":\"y\"} null", "{\"code\":\"x\",\"name\":\"y\",\"siteType\":{}}"))
            assertThrows(ApiResponseException.class,()->read(reader,input));
    }
    @Test void allNeutralFailureKindsTranslateToOwnedExceptionsAndUnexpectedFailuresStayUnexpected() {
        for(var kind:SiteMutationFailure.Kind.values()) assertInstanceOf(ApiResponseException.class,SiteApiFailures.translate(new SiteMutationFailure(kind)));
        var unexpected=new IllegalStateException("private");assertSame(unexpected,SiteApiFailures.translate(unexpected));
        for(var code:SiteResponseCode.values()) {
            if(code.getStatus()==400 && (code.getCode().startsWith("site-code-")||code.getCode().startsWith("site-name-")||code.getCode().startsWith("site-type-")))
                assertInstanceOf(ApiResponseException.class,SiteApiFailures.translate(new com.alexastudillo.companyregistry.domain.text.InvalidText(code.getCode())));
        }
    }
    private static UpdateSiteRequest read(UpdateSiteRequestReader reader,String text) {
        return reader.readFrom(UpdateSiteRequest.class,UpdateSiteRequest.class,new java.lang.annotation.Annotation[0],
                MediaType.APPLICATION_JSON_TYPE,new MultivaluedHashMap<>(),new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
