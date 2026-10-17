package com.alexastudillo.companyregistry.api.validation;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Apply explicitly to business-text fields, never globally to String or opaque values. */
public class BusinessTextDeserializer extends JsonDeserializer<String> {
    @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) return (String) context.handleUnexpectedToken(String.class, parser);
        return BusinessText.canonical(parser.getText());
    }
}
