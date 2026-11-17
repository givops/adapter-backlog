package io.givops.adapters.backlog;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.port.RepositoryContentHost;
import io.givops.ticketing.port.TicketAssetAdapter;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class BacklogRegistrationTest {
    @Test void plainJarRegistersAllDeclaredCapabilitiesThroughPublicHostServices() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BacklogAutoConfiguration.class))
                .withBean(RepositoryContentHost.class, MemoryRepository::new)
                .withBean(ContextSigner.class, () -> AdapterTestkit.signer("synthetic"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TicketProviderDefinition.class)
                            .hasSingleBean(TicketProviderAdapter.class).hasSingleBean(TicketBrowseAdapter.class)
                            .hasSingleBean(TicketContentAdapter.class).hasSingleBean(TicketAssetAdapter.class);
                    AdapterTestkit.verifyDefinition(context.getBean(TicketProviderDefinition.class));
                });
    }
    @Test void artifactMetadataAndBootImportsMatchDefinition() throws Exception {
        var properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/givops-adapter.properties")) { properties.load(stream); }
        var definition = new BacklogDefinition();
        assertThat(properties.getProperty("provider")).isEqualTo(definition.providerId());
        assertThat(properties.getProperty("sdkApiVersion")).isEqualTo(definition.sdkApiVersion()).isEqualTo("0.3");
        assertThat(properties.getProperty("adapterId")).isEqualTo(definition.adapterId());
        assertThat(properties.getProperty("adapterVersion")).isEqualTo(definition.adapterVersion());
        try (var stream = getClass().getResourceAsStream("/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).contains(BacklogAutoConfiguration.class.getName());
        }
    }
}
