package com.alexastudillo.companyregistry.api.site;

import static com.alexastudillo.companyregistry.api.site.SiteResponseCode.*;
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
public class EmptySiteCommandReader implements MessageBodyReader<EmptySiteCommand> {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
        return type == EmptySiteCommand.class;
    }
    @Override public EmptySiteCommand readFrom(Class<EmptySiteCommand> type, Type generic, Annotation[] annotations,
            MediaType media, MultivaluedMap<String, String> headers, InputStream stream) {
        try (var parser = JSON.createParser(stream)) {
            var root = parser.nextToken();
            if (root == null) return new EmptySiteCommand();
            var document = JSON.readTree(parser);
            if (parser.nextToken() != null) throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
            if (root != JsonToken.START_OBJECT) throw SiteApiFailures.error(REQUEST_BODY_MUST_BE_OBJECT);
            if (document.size() != 0) throw SiteApiFailures.error(REQUEST_BODY_CONTAINS_UNKNOWN_FIELDS);
            return new EmptySiteCommand();
        } catch (IOException _) {
            throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
        }
    }
}
