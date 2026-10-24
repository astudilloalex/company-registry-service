package com.alexastudillo.companyregistry.infrastructure.outbox;

import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@ApplicationScoped
public class DeliveryContexts {
    private final Vertx vertx;

    @Inject
    public DeliveryContexts(Vertx vertx) {
        this.vertx = vertx;
    }

    public <T> Uni<T> isolated(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> {
            var context = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
            io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setContextSafe(context, true);
            return Uni.createFrom().deferred(operation::get).runSubscriptionOn(executor(context)).emitOn(executor(context));
        });
    }

    public static Executor executor(Context context) {
        return action -> context.runOnContext(ignored -> action.run());
    }
}
