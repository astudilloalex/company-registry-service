package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.alexastudillo.companyregistry.application.persistence.OutboxRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Storage selection must not silently enable writes or equate published with an implemented sender. */
class OutboxPolicyTest {
    @Test void disabledSkipsStorageAndOtherModesStoreExactlyOnce() {
        var defaults = new io.smallrye.config.SmallRyeConfigBuilder().withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .withMapping(OutboxConfiguration.class).build()
                .getConfigMapping(OutboxConfiguration.class);
        assertEquals("disabled", defaults.mode());
        for (var value : new String[]{"disabled", "stored-only", "published"}) {
            var config = new io.smallrye.config.SmallRyeConfigBuilder().withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                    .withMapping(OutboxConfiguration.class)
                    .withDefaultValue("company-registry.outbox.mode", value).build().getConfigMapping(OutboxConfiguration.class);
            var repository = mock(OutboxRepository.class); var event = mock(OutboxRecord.class);
            when(repository.insert(event)).thenReturn(Uni.createFrom().item(event));
            var policy = new OutboxPolicy(config, repository);
            var stored = policy.store(event).await().atMost(Duration.ofSeconds(1));
            if (value.equals("disabled")) { assertNull(stored); verifyNoInteractions(repository); }
            else { assertSame(event, stored); verify(repository).insert(event); verifyNoMoreInteractions(repository); }
        }
    }
    @Test void rejectsUnknownModes() {
        for (var mode : new String[]{"", "invalid", "stored", "DISABLED", " disabled"}) {
            assertThrows(IllegalArgumentException.class, () -> OutboxMode.parse(mode));
        }
    }
}
