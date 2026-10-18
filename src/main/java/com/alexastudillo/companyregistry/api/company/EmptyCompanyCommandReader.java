package com.alexastudillo.companyregistry.api.company;

import static com.alexastudillo.companyregistry.api.company.CompanyLifecycleResponseCode.*;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@Provider @Consumes(MediaType.APPLICATION_JSON) @ConstrainedTo(RuntimeType.SERVER)
public class EmptyCompanyCommandReader implements MessageBodyReader<EmptyCompanyCommand> {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
        return type == EmptyCompanyCommand.class;
    }
    @Override public EmptyCompanyCommand readFrom(Class<EmptyCompanyCommand> type, Type generic, Annotation[] annotations,
            MediaType media, MultivaluedMap<String, String> headers, InputStream stream) {
        try (var parser = JSON.createParser(stream)) {
            var root = parser.nextToken();
            if (root == null) return EmptyCompanyCommand.INSTANCE;
            var document = JSON.readTree(parser);
            if (parser.nextToken() != null) throw CompanyLifecycleApiFailures.error(REQUEST_BODY_INVALID_JSON);
            if (root != JsonToken.START_OBJECT) throw CompanyLifecycleApiFailures.error(REQUEST_BODY_MUST_BE_OBJECT);
            if (document.size() != 0) throw CompanyLifecycleApiFailures.error(REQUEST_BODY_CONTAINS_UNKNOWN_FIELDS);
            return EmptyCompanyCommand.INSTANCE;
        } catch (IOException _) {
            throw CompanyLifecycleApiFailures.error(REQUEST_BODY_INVALID_JSON);
        }
    }
}
