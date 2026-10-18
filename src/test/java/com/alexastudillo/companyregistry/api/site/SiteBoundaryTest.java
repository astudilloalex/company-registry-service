package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.application.site.SiteFailure;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteBoundaryTest {
    @Test void readerPreservesRawStructuredInputAndPresenceWithoutDestructiveCanonicalization() {
        var reader=new CreateSiteRequestReader();
        var request=read(reader,"{\"name\":\" compañía  ágil \",\"code\":\" ß \"}");
        assertEquals(" ß ",request.code());assertEquals(" compañía  ágil ",request.name());
        assertEquals(FieldChange.absent(),request.siteType());
        var explicit=read(reader,"{\"code\":\"x\",\"name\":\"y\",\"siteType\":\"BRANCH\"}");
        assertEquals(FieldChange.present("BRANCH"),explicit.siteType());
    }
    @Test void readerChecksFullStructureAndEarlierFieldsBeforeLaterFieldDiagnostics() {
        var reader=new CreateSiteRequestReader();
        for(var input:List.of("{\"code\":1,\"extra\":false}","{\"code\":1,\"code\":2}",
                "{\"name\":false,\"code\":null}","{\"code\":\"x\",\"siteType\":false}",
                "{\"code\":\"x\",\"name\":\"y\"} null", "{\"code\":\"x\",\"name\":\"y\",\"siteType\":{}}"))
            assertThrows(ApiResponseException.class,()->read(reader,input));
    }
    @Test void allNeutralFailureKindsTranslateToOwnedExceptionsAndUnexpectedFailuresStayUnexpected() {
        for(var kind:SiteFailure.Kind.values()) assertInstanceOf(ApiResponseException.class,SiteApiFailures.translate(new SiteFailure(kind)));
        var unexpected=new IllegalStateException("private");assertSame(unexpected,SiteApiFailures.translate(unexpected));
        for(var code:SiteResponseCode.values()) {
            if(code.getStatus()==400 && (code.getCode().startsWith("site-code-")||code.getCode().startsWith("site-name-")||code.getCode().startsWith("site-type-")))
                assertInstanceOf(ApiResponseException.class,SiteApiFailures.translate(new com.alexastudillo.companyregistry.domain.text.InvalidText(code.getCode())));
        }
    }
    private static CreateSiteRequest read(CreateSiteRequestReader reader,String text) {
        return reader.readFrom(CreateSiteRequest.class,CreateSiteRequest.class,new java.lang.annotation.Annotation[0],
                MediaType.APPLICATION_JSON_TYPE,new MultivaluedHashMap<>(),new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
