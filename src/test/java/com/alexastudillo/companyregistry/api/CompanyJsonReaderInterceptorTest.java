package com.alexastudillo.companyregistry.api;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.api.error.CompanyJsonReaderInterceptor;
import com.alexastudillo.companyregistry.api.error.CompanyResponseCode;
import com.alexastudillo.companyregistry.api.verification.ProbeRequest;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ensures owned input translation does not hide server defects, I/O failures or unrelated readers. */
class CompanyJsonReaderInterceptorTest {
    private final CompanyJsonReaderInterceptor interceptor = new CompanyJsonReaderInterceptor();

    @Test void delegatesSuccessAndRestrictsTranslationToOwnedBodies() throws IOException {
        var result = new ProbeRequest("READY");
        assertSame(result, interceptor.aroundReadFrom(context(ProbeRequest.class, () -> result)));
        var failure = MismatchedInputException.from((JsonParser) null, String.class, "private-input");
        var context = context(String.class, () -> { throw failure; });
        assertSame(failure, assertThrows(MismatchedInputException.class,
                () -> interceptor.aroundReadFrom(context)));
    }

    @Test void translatesOnlyClientInputErrorsToTheServiceOwnedCode() {
        for (var failure : List.of(new JsonParseException((JsonParser) null, "private-input"),
                MismatchedInputException.from((JsonParser) null, ProbeRequest.class, "private-input"),
                new StreamConstraintsException("private-input"))) {
            var context = context(ProbeRequest.class, () -> { throw failure; });
            var translated = assertThrows(ApiResponseException.class,
                    () -> interceptor.aroundReadFrom(context));
            assertSame(CompanyResponseCode.BAD_REQUEST, translated.getResponseCode());
            assertEquals(400, translated.getResponseCode().getStatus());
            assertNull(translated.getCause());
        }
    }

    @Test void preservesServerDefinitionAndIoFailures() {
        var definition = InvalidDefinitionException.from((JsonParser) null, "private-definition", (JavaType) null);
        for (IOException failure : List.of(definition, new IOException("private-io"))) {
            var context = context(ProbeRequest.class, () -> { throw failure; });
            assertSame(failure, assertThrows(IOException.class,
                    () -> interceptor.aroundReadFrom(context)));
        }
    }

    private static ReaderInterceptorContext context(Class<?> type, Read read) {
        return (ReaderInterceptorContext) Proxy.newProxyInstance(ReaderInterceptorContext.class.getClassLoader(),
                new Class<?>[]{ReaderInterceptorContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "proceed" -> read.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private interface Read { Object get() throws IOException; }
}
