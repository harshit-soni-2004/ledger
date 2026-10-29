package dev.ihars.ledger.shared.archfixture;

import dev.ihars.ledger.ledger.infrastructure.archfixture.LedgerTableGateway;

/** Fixture for {@code ArchitectureRulesTest}: shared code that depends on a feature module. */
public class SharedReachingIntoFeature {

    LedgerTableGateway gateway = new LedgerTableGateway();
}
