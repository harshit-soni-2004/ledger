package dev.ihars.ledger.architecture.archfixture.domain;

import org.springframework.util.Assert;

/** Fixture for {@code ArchitectureRulesTest}: a domain class that depends on Spring. */
public class SpringAwareDomainObject {

    public SpringAwareDomainObject() {
        Assert.state(true, "fixture");
    }
}
