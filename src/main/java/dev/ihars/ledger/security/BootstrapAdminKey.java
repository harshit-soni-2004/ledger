package dev.ihars.ledger.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Registers {@code LEDGER_BOOTSTRAP_ADMIN_KEY} as the first ADMIN key at startup (local/demo
 * only; leave it unset in production). Only the hash is stored.
 */
@Component
class BootstrapAdminKey implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminKey.class);

    private final ApiClientService clients;
    private final String token;

    BootstrapAdminKey(ApiClientService clients, @Value("${ledger.security.bootstrap-admin-key:}") String token) {
        this.clients = clients;
        this.token = token;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (token == null || token.isBlank()) {
            return;
        }
        if (clients.bootstrapAdmin(token.strip())) {
            log.info("Registered the bootstrap ADMIN key (prefix {})", ApiKeys.displayPrefix(token.strip()));
        }
    }
}
