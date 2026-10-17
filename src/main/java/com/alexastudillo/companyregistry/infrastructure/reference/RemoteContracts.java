package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure.Kind;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.ProcessingException;
import org.jboss.resteasy.reactive.RestResponse;

/** Strict envelope validation and sanitized provider-failure classification. */
final class RemoteContracts {
    private RemoteContracts() {}
    static <T> T data(RestResponse<RemoteModels.Envelope<T>> response) {
        try (response) {
            int status = response.getStatus();
            if (status == 404) return null;
            if (status >= 500 || status == 429) throw new ReferenceFailure(Kind.UNAVAILABLE);
            if (status != 200) throw invalid();
            // Quarkus may retain non-2xx bodies as streams; classify status first
            // and close every response instead of coercing an error payload into a success DTO.
            var envelope = response.getEntity();
            if (envelope == null || envelope.status() == null || envelope.status() != status
                    || !"successful".equals(envelope.code()) || envelope.data() == null) throw invalid();
            return envelope.data();
        }
    }
    static ReferenceFailure invalid() { return new ReferenceFailure(Kind.INVALID_CONTRACT); }
    static boolean clientFailure(Throwable failure) {
        if (failure instanceof ReferenceFailure || failure instanceof java.util.concurrent.CancellationException) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException || cause instanceof ProcessingException || cause instanceof jakarta.ws.rs.WebApplicationException
                    || cause instanceof java.net.ConnectException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException) return true;
        }
        return false;
    }
    static ReferenceFailure processing(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException) return invalid();
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) return new ReferenceFailure(Kind.TIMEOUT);
        }
        return new ReferenceFailure(Kind.UNAVAILABLE);
    }
}
