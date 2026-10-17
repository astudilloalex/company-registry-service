package com.alexastudillo.companyregistry.api.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Preserve structured input for versioned policies without accepting scalar coercion. */
public class StrictStringDeserializer extends JsonDeserializer<String> {
    @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) return (String) context.handleUnexpectedToken(String.class, parser);
        return parser.getText();
    }
}
