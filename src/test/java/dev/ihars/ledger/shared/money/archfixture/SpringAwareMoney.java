package dev.ihars.ledger.shared.money.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture for ArchitectureRulesTest: a money type that depends on Spring. */
public class SpringAwareMoney {

    JdbcClient jdbc;
}
