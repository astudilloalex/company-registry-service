package com.alexastudillo.companyregistry.api.site;

import static com.alexastudillo.companyregistry.api.site.SiteResponseCode.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.fasterxml.jackson.core.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

/** Reads the entire document before selecting one stable structural/field error. */
@Provider @Consumes(MediaType.APPLICATION_JSON) @ConstrainedTo(RuntimeType.SERVER)
public class CreateSiteRequestReader implements MessageBodyReader<CreateSiteRequest> {
    private static final JsonFactory JSON = new JsonFactory();
    private static final String SITE_TYPE_FIELD = "siteType";
    private static final Set<String> ALLOWED = Set.of("code", "name", SITE_TYPE_FIELD);
    private record ParsedDocument(
            Map<String, String> values,
            Set<String> seen,
            Set<String> wrongTypes,
            boolean unknown,
            boolean duplicate) {}

    @Override public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType mediaType) {
        return type == CreateSiteRequest.class;
    }

    @Override public CreateSiteRequest readFrom(Class<CreateSiteRequest> type, Type generic, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, String> headers, InputStream stream) {
        try (var parser = JSON.createParser(stream)) {
            var document = parseDocument(parser);
            validateDocument(document);
            return toRequest(document);
        } catch (IOException _) {
            throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
        } catch (com.alexastudillo.companyregistry.domain.text.InvalidText failure) {
            throw (RuntimeException) SiteApiFailures.translate(failure);
        }
    }

    private ParsedDocument parseDocument(JsonParser parser) throws IOException {
        validateRoot(parser);
        var values = new HashMap<String, String>();
        var seen = new HashSet<String>();
        var wrongTypes = new HashSet<String>();
        boolean unknown = false;
        boolean duplicate = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
            var field = parser.currentName();
            unknown |= !ALLOWED.contains(field);
            duplicate |= !seen.add(field);
            readFieldValue(parser, field, values, wrongTypes);
        }
        if (parser.nextToken() != null) throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
        return new ParsedDocument(values, seen, wrongTypes, unknown, duplicate);
    }

    private void validateRoot(JsonParser parser) throws IOException {
        var root = parser.nextToken();
        if (root == null) throw SiteApiFailures.error(REQUEST_BODY_REQUIRED);
        if (root != JsonToken.START_OBJECT) {
            parser.skipChildren();
            if (parser.nextToken() != null) throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
            throw SiteApiFailures.error(REQUEST_BODY_MUST_BE_OBJECT);
        }
    }

    private void readFieldValue(JsonParser parser, String field, Map<String, String> values, Set<String> wrongTypes) throws IOException {
        var token = parser.nextToken();
        if (token == null) throw SiteApiFailures.error(REQUEST_BODY_INVALID_JSON);
        switch (token) {
            case VALUE_STRING -> values.put(field, parser.getText());
            case VALUE_NULL -> values.put(field, null);
            default -> {
                wrongTypes.add(field);
                parser.skipChildren();
            }
        }
    }

    private void validateDocument(ParsedDocument doc) {
        if (doc.unknown()) throw SiteApiFailures.error(REQUEST_BODY_CONTAINS_UNKNOWN_FIELDS);
        if (doc.duplicate()) throw SiteApiFailures.error(REQUEST_BODY_CONTAINS_DUPLICATE_FIELDS);
        validateFieldTypes(doc.values(), doc.wrongTypes());
    }

    private void validateFieldTypes(Map<String, String> values, Set<String> wrongTypes) {
        if (wrongTypes.contains("code")) throw SiteApiFailures.error(SITE_CODE_MUST_BE_STRING);
        // Earlier-field diagnostics precede a later incompatible input type. Ordinary
        // structured text stays raw: effective validation belongs to the acceptance's
        // selected fingerprint policy, including historical replay.
        if (wrongTypes.contains("name") || wrongTypes.contains(SITE_TYPE_FIELD)) SiteRegistrationPolicy.code(values.get("code"));
        if (wrongTypes.contains("name")) throw SiteApiFailures.error(SITE_NAME_MUST_BE_STRING);
        if (wrongTypes.contains(SITE_TYPE_FIELD)) {
            SiteRegistrationPolicy.canonical(values.get("code"), values.get("name"), FieldChange.absent());
            throw SiteApiFailures.error(SITE_TYPE_MUST_BE_STRING);
        }
    }

    private CreateSiteRequest toRequest(ParsedDocument doc) {
        var siteType = doc.seen().contains(SITE_TYPE_FIELD) ? FieldChange.present(doc.values().get(SITE_TYPE_FIELD)) : FieldChange.absent();
        // Canonical values are deliberately not stored in the request.
        return new CreateSiteRequest(doc.values().get("code"), doc.values().get("name"), siteType);
    }
}
