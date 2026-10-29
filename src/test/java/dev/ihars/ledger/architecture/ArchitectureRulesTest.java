package dev.ihars.ledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.ihars.ledger.architecture.archfixture.domain.SpringAwareDomainObject;
import dev.ihars.ledger.ledger.infrastructure.archfixture.LedgerTableGateway;
import dev.ihars.ledger.shared.archfixture.SharedReachingIntoFeature;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves each {@link ArchitectureTest} rule actually fails on a known violation, so a rule that
 * silently matches nothing cannot pass unnoticed. Fixtures live in test sources, which
 * {@link ArchitectureTest} does not import.
 */
class ArchitectureRulesTest {

    @Test
    void domainMustNotDependOnSpring() {
        assertViolated(ArchitectureTest.domainIsFrameworkFree, SpringAwareDomainObject.class);
    }

    @Test
    void sharedMustNotDependOnOtherApplicationPackages() {
        assertViolated(ArchitectureTest.sharedDependsOnNoFeatureModule, SharedReachingIntoFeature.class);
    }

    @Test
    void ledgerInfrastructureIsNotReachableFromOtherModules() {
        assertViolated(ArchitectureTest.ledgerInfrastructureIsPrivate, ReachesIntoLedgerTables.class);
    }

    @Test
    void bigDecimalMustNotBeBuiltFromDoubles() {
        assertViolated(ArchitectureTest.noFloatingPointMoney, DoubleConstructorMoney.class);
        assertViolated(ArchitectureTest.noFloatingPointMoney, DoubleValueOfMoney.class);
    }

    @Test
    void systemClockReadsAreRejectedButInjectedClocksAreAllowed() {
        assertViolated(ArchitectureTest.timeComesFromInjectedClock, InstantNow.class);
        assertViolated(ArchitectureTest.timeComesFromInjectedClock, LocalDateNowInZone.class);
        assertViolated(ArchitectureTest.timeComesFromInjectedClock, SystemClockFactory.class);
        assertViolated(ArchitectureTest.timeComesFromInjectedClock, LegacyDate.class);
        assertSatisfied(ArchitectureTest.timeComesFromInjectedClock, InjectedClockUser.class);
    }

    @Test
    void controllersMustNotBeTransactional() {
        assertViolated(ArchitectureTest.controllersAreNotTransactional, TransactionalController.class);
        assertViolated(ArchitectureTest.controllerMethodsAreNotTransactional, TransactionalHandlerMethod.class);
    }

    @Test
    void fieldInjectionIsRejected() {
        assertViolated(ArchitectureTest.noFieldInjection, FieldInjected.class);
    }

    private static void assertViolated(ArchRule rule, Class<?> fixture) {
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(fixture)).hasViolation())
                .as("%s should be violated by %s", rule.getDescription(), fixture.getSimpleName())
                .isTrue();
    }

    private static void assertSatisfied(ArchRule rule, Class<?> fixture) {
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(fixture)).hasViolation())
                .as("%s should accept %s", rule.getDescription(), fixture.getSimpleName())
                .isFalse();
    }

    static class ReachesIntoLedgerTables {
        Object gateway = new LedgerTableGateway();
    }

    static class DoubleConstructorMoney {
        BigDecimal amount() {
            return new BigDecimal(0.1);
        }
    }

    static class DoubleValueOfMoney {
        BigDecimal amount() {
            return BigDecimal.valueOf(0.1);
        }
    }

    static class InstantNow {
        Instant at() {
            return Instant.now();
        }
    }

    static class LocalDateNowInZone {
        LocalDate today() {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    static class SystemClockFactory {
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    static class LegacyDate {
        Date now() {
            return new Date();
        }
    }

    static class InjectedClockUser {
        Instant at(Clock clock) {
            return Instant.now(clock);
        }
    }

    @RestController
    @Transactional
    static class TransactionalController {}

    @RestController
    static class TransactionalHandlerMethod {
        @Transactional
        void handle() {}
    }

    static class FieldInjected {
        @Autowired
        Object dependency;
    }
}
