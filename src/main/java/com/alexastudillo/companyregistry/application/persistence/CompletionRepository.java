package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;

public interface CompletionRepository {
    Uni<CompletionRecord> find(CompletionKey key);
    Uni<CompletionRecord> insert(CompletionRecord completion);
}
