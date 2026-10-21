package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import com.fasterxml.jackson.core.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

/** Reads the complete JSON structure before applying fixed-order HU07 field validation. */
@Provider @Consumes(MediaType.APPLICATION_JSON) @ConstrainedTo(RuntimeType.SERVER)
public class ContactRequestReader implements MessageBodyReader<Object> {
    private static final JsonFactory JSON = new JsonFactory();
    private static final Object WRONG_TYPE = new Object();
    private static final String REQUEST_BODY_INVALID_JSON = "request-body-invalid-json";
    private static final String IS_PRIMARY = "isPrimary";
    private static final List<String> FIELDS = List.of("contactType", "value", "label", IS_PRIMARY);
    private static final List<String> RULES = List.of("contact-type", "contact-value", "contact-label");
    private record Document(Map<String, Object> values, boolean unknown, boolean duplicate) {}

    @Override public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
        return type == CreateContactRequest.class || type == UpdateContactRequest.class || type == EmptyContactCommand.class;
    }

    @Override public Object readFrom(Class<Object> type, Type generic, Annotation[] annotations, MediaType media,
            MultivaluedMap<String, String> headers, InputStream stream) {
        boolean empty = (Class<?>) type == EmptyContactCommand.class;
        boolean create = (Class<?>) type == CreateContactRequest.class;
        try (var parser = JSON.createParser(stream)) {
            var document = parse(parser, empty);
            if (document.unknown() || empty && !document.values().isEmpty()) throw ContactApiFailures.error("request-body-contains-unknown-fields");
            if (document.duplicate()) throw ContactApiFailures.error("request-body-contains-duplicate-fields");
            if (empty) return EmptyContactCommand.INSTANCE;
            validate(document.values(), create);
            var patch = patch(document.values());
            return create ? new CreateContactRequest(patch) : new UpdateContactRequest(patch);
        } catch (IOException _) {
            throw ContactApiFailures.error(REQUEST_BODY_INVALID_JSON);
        } catch (InvalidText failure) {
            throw ContactApiFailures.error(failure.rule());
        }
    }

    private static Document parse(JsonParser parser, boolean emptyAllowed) throws IOException {
        var root = parser.nextToken();
        if (root == null) {
            if (emptyAllowed) return new Document(Map.of(), false, false);
            throw ContactApiFailures.error("request-body-required");
        }
        if (root != JsonToken.START_OBJECT) {
            parser.skipChildren();
            if (parser.nextToken() != null) throw ContactApiFailures.error(REQUEST_BODY_INVALID_JSON);
            throw ContactApiFailures.error("request-body-must-be-object");
        }
        var values = new HashMap<String, Object>();
        boolean unknown = false;
        boolean duplicate = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw ContactApiFailures.error(REQUEST_BODY_INVALID_JSON);
            var name = parser.currentName();
            unknown |= !FIELDS.contains(name);
            duplicate |= values.containsKey(name);
            var token = parser.nextToken();
            if (token == null) throw ContactApiFailures.error(REQUEST_BODY_INVALID_JSON);
            Object value = switch (token) {
                case VALUE_NULL -> null;
                case VALUE_STRING -> parser.getText();
                case VALUE_TRUE -> true;
                case VALUE_FALSE -> false;
                case VALUE_NUMBER_FLOAT, VALUE_NUMBER_INT -> WRONG_TYPE;
                default -> WRONG_TYPE;
            };
            values.put(name, value);
            parser.skipChildren();
        }
        if (parser.nextToken() != null) throw ContactApiFailures.error(REQUEST_BODY_INVALID_JSON);
        return new Document(values, unknown, duplicate);
    }

    private static void validate(Map<String, Object> values, boolean create) {
        // Structural types and required values for every field precede semantic canonicalization.
        for (int i = 0; i < 3; i++) {
            String name = FIELDS.get(i);
            Object value = values.get(name);
            if (value != null && !(value instanceof String)) throw ContactApiFailures.error(RULES.get(i) + "-must-be-string");
            if (i < 2 && (create || values.containsKey(name)) && (value == null || ((String) value).isBlank())) {
                throw ContactApiFailures.error(RULES.get(i) + "-required");
            }
        }
        if (values.containsKey(IS_PRIMARY) && !(values.get(IS_PRIMARY) instanceof Boolean)) {
            throw ContactApiFailures.error("contact-is-primary-must-be-boolean");
        }
    }

    private static ContactInformationPatch patch(Map<String, Object> values) {
        return new ContactInformationPatch(field(values, "contactType"), field(values, "value"), field(values, "label"),
                new ContactInformationPatch.PrimaryChange(values.containsKey(IS_PRIMARY), (Boolean) values.get(IS_PRIMARY)));
    }

    private static FieldChange field(Map<String, Object> values, String name) {
        return values.containsKey(name) ? FieldChange.present((String) values.get(name)) : FieldChange.absent();
    }
}
