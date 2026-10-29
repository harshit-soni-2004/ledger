package dev.ihars.ledger.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module boundaries and coding rules from docs/01-architecture.md and docs/09-testing-strategy.md.
 * Rules over packages that do not exist yet are declared now (allowEmptyShould) so they start
 * guarding the moment the first class lands there.
 */
@AnalyzeClasses(packages = "dev.ihars.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "dev.ihars.ledger";

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + "..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..", "jakarta.persistence..")
            .allowEmptyShould(true)
            .because("domain rules must be testable without Spring or a database");

    @ArchTest
    static final ArchRule sharedDependsOnNoFeatureModule = noClasses()
            .that().resideInAPackage(ROOT + ".shared..")
            .should().dependOnClassesThat(
                    resideInAPackage(ROOT + "..").and(not(resideInAPackage(ROOT + ".shared.."))))
            .because("shared is the bottom layer");

    @ArchTest
    static final ArchRule ledgerInfrastructureIsPrivate = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".ledger..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".ledger.infrastructure..")
            .allowEmptyShould(true)
            .because("only the ledger module may touch ledger tables; others use its public API");

    @ArchTest
    static final ArchRule onlyThePostingEngineWritesTheLedger = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".ledger.posting..", ROOT + ".ledger.infrastructure..")
            .should().dependOnClassesThat(
                    resideInAPackage(ROOT + ".ledger.infrastructure..").and(simpleNameEndingWith("Writer")))
            .because("journals, entries and balances have a single writer (ADR-0005, ADR-0008)");

    @ArchTest
    static final ArchRule moneyIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + ".shared.money..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..", "tools.jackson..",
                    "com.fasterxml..")
            .because("Money is a value type shared by every layer, including the framework-free domain");

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = slices()
            .matching(ROOT + ".(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFloatingPointMoney = noClasses()
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .because("money must be built from strings or longs, never doubles (ADR-0004)");

    private static final DescribedPredicate<JavaMethodCall> readsSystemClock = DescribedPredicate.describe(
            "reads the system clock",
            call -> {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                boolean takesClock = call.getTarget().getRawParameterTypes().stream()
                        .anyMatch(type -> type.isEquivalentTo(Clock.class));
                return (call.getTargetOwner().getPackageName().equals("java.time") && name.equals("now") && !takesClock)
                        || (owner.equals(Clock.class.getName()) && name.startsWith("system"))
                        || (owner.equals(System.class.getName()) && name.equals("currentTimeMillis"));
            });

    @ArchTest
    static final ArchRule timeComesFromInjectedClock = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".shared.time..")
            .should().callMethodWhere(readsSystemClock)
            .orShould().callConstructor(Date.class)
            .because("time must come from an injected Clock so tests are deterministic");

    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllerMethodsAreNotTransactional = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("constructor injection keeps dependencies explicit and testable");
}
