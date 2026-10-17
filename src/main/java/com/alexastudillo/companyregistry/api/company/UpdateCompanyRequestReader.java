package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.domain.model.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.util.*;

/** Closed root body; preserve raw values for historical fingerprint policy selection. */
public final class UpdateCompanyRequestReader extends StdDeserializer<UpdateCompanyRequest> {
    public UpdateCompanyRequestReader() {super(UpdateCompanyRequest.class);}
    @Override public UpdateCompanyRequest getNullValue(DeserializationContext context) throws JsonMappingException {
        throw MismatchedInputException.from(context.getParser(), UpdateCompanyRequest.class, "body-invalid");
    }
    @Override public UpdateCompanyRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if(!parser.isExpectedStartObjectToken()) throw invalid(parser);
        var fields=new HashMap<String,FieldChange>();
        while(parser.nextToken()!=JsonToken.END_OBJECT) {
            if(parser.currentToken()!=JsonToken.FIELD_NAME) throw invalid(parser);
            var name=parser.currentName();
            if(!Set.of("code","operatingName","description","logoUrl").contains(name) || fields.containsKey(name)) throw invalid(parser);
            var token=parser.nextToken();
            if(token!=JsonToken.VALUE_STRING && token!=JsonToken.VALUE_NULL) throw invalid(parser);
            fields.put(name,FieldChange.present(token==JsonToken.VALUE_NULL?null:parser.getText()));
        }
        if(parser.nextToken()!=null) throw invalid(parser);
        return new UpdateCompanyRequest(new CompanyInformationPatch(fields.getOrDefault("code",FieldChange.absent()),
                fields.getOrDefault("operatingName",FieldChange.absent()),fields.getOrDefault("description",FieldChange.absent()),
                fields.getOrDefault("logoUrl",FieldChange.absent())));
    }
    private MismatchedInputException invalid(JsonParser parser) {return MismatchedInputException.from(parser,UpdateCompanyRequest.class,"body-invalid");}
}
