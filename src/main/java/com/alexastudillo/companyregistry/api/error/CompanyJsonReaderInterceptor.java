package com.alexastudillo.companyregistry.api.error;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.api.validation.CompanyJsonBody;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;

/**
 * Translates input errors for owned DTOs; the shared mapper still produces
 * every final error response.
 */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
public class CompanyJsonReaderInterceptor implements ReaderInterceptor {
    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
        if (!context.getType().isAnnotationPresent(CompanyJsonBody.class))
            return context.proceed();
        try {
            return context.proceed();
        } catch (JsonParseException | MismatchedInputException | StreamConstraintsException _) {
            // Submitted values and unknown property names must not become public messages
            // or log details.
            throw new ApiResponseException(CompanyResponseCode.BAD_REQUEST);
        }
    }
}
