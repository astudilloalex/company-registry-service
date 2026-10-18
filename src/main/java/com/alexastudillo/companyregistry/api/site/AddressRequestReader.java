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
import java.math.BigDecimal;
import java.util.*;

/** Reads the complete JSON structure before applying fixed-order HU06 field validation. */
@Provider @Consumes(MediaType.APPLICATION_JSON) @ConstrainedTo(RuntimeType.SERVER)
public class AddressRequestReader implements MessageBodyReader<Object> {
    private static final JsonFactory JSON = new JsonFactory();
    private static final Object WRONG_TYPE = new Object();
    private static final String REQUEST_BODY_INVALID_JSON = "request-body-invalid-json";
    private static final String IS_PRIMARY = "isPrimary";
    private static final String LATITUDE = "latitude";
    private static final String LONGITUDE = "longitude";
    private static final List<String> FIELDS = List.of("addressType", "countryCode", "addressLine1",
            "administrativeDivisionCode", "addressLine2", "houseNumber", "postalCode", IS_PRIMARY, LATITUDE, LONGITUDE);
    private static final List<String> RULES = List.of("address-type", "address-country-code", "address-line1",
            "address-administrative-division-code", "address-line2", "address-house-number", "address-postal-code");
    private record Document(Map<String, Object> values, boolean unknown, boolean duplicate) {}

    @Override public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
        return type == CreateAddressRequest.class || type == UpdateAddressRequest.class || type == EmptyAddressCommand.class;
    }

    @Override public Object readFrom(Class<Object> type, Type generic, Annotation[] annotations, MediaType media,
            MultivaluedMap<String, String> headers, InputStream stream) {
        boolean empty = (Class<?>) type == EmptyAddressCommand.class;
        boolean create = (Class<?>) type == CreateAddressRequest.class;
        try (var parser = JSON.createParser(stream)) {
            var document = parse(parser, empty);
            if (document.unknown() || empty && !document.values().isEmpty()) throw AddressApiFailures.error("request-body-contains-unknown-fields");
            if (document.duplicate()) throw AddressApiFailures.error("request-body-contains-duplicate-fields");
            if (empty) return EmptyAddressCommand.INSTANCE;
            validate(document.values(), create);
            var patch = patch(document.values(), 9, false);
            return create ? new CreateAddressRequest(patch) : new UpdateAddressRequest(patch);
        } catch (IOException _) {
            throw AddressApiFailures.error(REQUEST_BODY_INVALID_JSON);
        } catch (InvalidText failure) {
            throw AddressApiFailures.error(failure.rule());
        }
    }

    private static Document parse(JsonParser parser, boolean emptyAllowed) throws IOException {
        var root = parser.nextToken();
        if (root == null) {
            if (emptyAllowed) return new Document(Map.of(), false, false);
            throw AddressApiFailures.error("request-body-required");
        }
        if (root != JsonToken.START_OBJECT) {
            parser.skipChildren();
            if (parser.nextToken() != null) throw AddressApiFailures.error(REQUEST_BODY_INVALID_JSON);
            throw AddressApiFailures.error("request-body-must-be-object");
        }
        var values = new HashMap<String, Object>();
        boolean unknown = false;
        boolean duplicate = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw AddressApiFailures.error(REQUEST_BODY_INVALID_JSON);
            var name = parser.currentName();
            unknown |= !FIELDS.contains(name);
            duplicate |= values.containsKey(name);
            var token = parser.nextToken();
            if (token == null) throw AddressApiFailures.error(REQUEST_BODY_INVALID_JSON);
            Object value = switch (token) {
                case VALUE_NULL -> null;
                case VALUE_STRING -> parser.getText();
                case VALUE_TRUE -> true;
                case VALUE_FALSE -> false;
                case VALUE_NUMBER_FLOAT, VALUE_NUMBER_INT -> parser.getDecimalValue();
                default -> WRONG_TYPE;
            };
            values.put(name, value);
            parser.skipChildren();
        }
        if (parser.nextToken() != null) throw AddressApiFailures.error(REQUEST_BODY_INVALID_JSON);
        return new Document(values, unknown, duplicate);
    }

    private static void validate(Map<String, Object> values, boolean create) {
        for (int i = 0; i < 7; i++) {
            var value = values.get(FIELDS.get(i));
            if (value != null && !(value instanceof String)) throw AddressApiFailures.error(RULES.get(i) + "-must-be-string");
            AddressInformationPolicy.canonical(patch(values, i, create));
        }
        if (values.containsKey(IS_PRIMARY) && !(values.get(IS_PRIMARY) instanceof Boolean)) {
            throw AddressApiFailures.error("address-is-primary-must-be-boolean");
        }
        boolean latitude = values.containsKey(LATITUDE);
        boolean longitude = values.containsKey(LONGITUDE);
        if (latitude != longitude) throw AddressApiFailures.error("address-coordinates-must-be-provided-together");
        for (String field : List.of(LATITUDE, LONGITUDE)) {
            if (values.get(field) != null && !(values.get(field) instanceof BigDecimal)) {
                throw AddressApiFailures.error("address-" + field + "-must-be-number");
            }
        }
        AddressInformationPolicy.canonical(patch(values, 9, create));
    }

    private static AddressInformationPatch patch(Map<String, Object> values, int through, boolean required) {
        var text = new AddressInformationPatch.TextChanges(field(values, 1, through, required), field(values, 2, through, required),
                field(values, 3, through, false), field(values, 4, through, false), field(values, 5, through, false), field(values, 6, through, false));
        return new AddressInformationPatch(field(values, 0, through, required), text,
                new AddressInformationPatch.PrimaryChange(through >= 7 && values.containsKey(IS_PRIMARY),
                        through >= 7 ? (Boolean) values.get(IS_PRIMARY) : null),
                through >= 8 ? new AddressCoordinates.Change(values.containsKey(LATITUDE), (BigDecimal) values.get(LATITUDE),
                        values.containsKey(LONGITUDE), (BigDecimal) values.get(LONGITUDE)) : AddressCoordinates.Change.absent());
    }

    private static FieldChange field(Map<String, Object> values, int index, int through, boolean required) {
        var name = FIELDS.get(index);
        return through >= index && (required || values.containsKey(name)) ? FieldChange.present((String) values.get(name)) : FieldChange.absent();
    }
}
