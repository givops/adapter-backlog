package io.givops.adapters.backlog;

import io.givops.adapters.ContextSigner;
import io.givops.ticketing.port.RepositoryContentHost;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class BacklogAutoConfiguration {
    @Bean BacklogDefinition backlogDefinition() { return new BacklogDefinition(); }
    @Bean BacklogAdapter backlogAdapter(RepositoryContentHost host, ContextSigner signer) { return new BacklogAdapter(host, signer); }
}
