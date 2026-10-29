package dev.ihars.ledger.support;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injects a seeded {@link RandomGenerator} into test methods (ADR-0002, property testing).
 *
 * <p>The default seed is derived from the test's class and method name, so every run of a
 * test sees the same inputs and CI is deterministic. Set {@code -Dledger.test.seed=<n>} to
 * explore other inputs or to replay a reported failure. A failure always reports its seed
 * and the command that reproduces it.
 */
public class SeededRandomExtension implements ParameterResolver, TestExecutionExceptionHandler {

    public static final String SEED_PROPERTY = "ledger.test.seed";

    private static final Logger log = LoggerFactory.getLogger(SeededRandomExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SeededRandomExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == RandomGenerator.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        long seed = seedFor(context);
        context.getStore(NAMESPACE).put("seed", seed);
        log.info("{} uses seed {}", testName(context), seed);
        return new SplittableRandom(seed);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        Long seed = context.getStore(NAMESPACE).get("seed", Long.class);
        if (seed == null) {
            throw failure;
        }
        boolean integrationTest = context.getRequiredTestClass().getSimpleName().endsWith("IT");
        AssertionError withSeed = new AssertionError("%s failed with seed %d. Reproduce: ./mvnw %s -D%s='%s' -D%s=%d"
                .formatted(testName(context), seed, integrationTest ? "verify" : "test",
                        integrationTest ? "it.test" : "test", testName(context), SEED_PROPERTY, seed), failure);
        throw withSeed;
    }

    private static long seedFor(ExtensionContext context) {
        String configured = System.getProperty(SEED_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Long.parseLong(configured.trim());
        }
        return testName(context).hashCode();
    }

    private static String testName(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName();
    }
}
