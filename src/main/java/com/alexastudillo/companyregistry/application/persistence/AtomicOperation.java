package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.function.Supplier;

/** Executes one sequential aggregate/completion/optional-event operation atomically. */
public interface AtomicOperation {
    <T> Uni<T> execute(Supplier<Uni<T>> operation);
    <T> Uni<T> read(Supplier<Uni<T>> operation);
}
