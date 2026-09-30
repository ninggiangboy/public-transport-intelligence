package dev.pti.db;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import dev.pti.testing.PtiArchitectureRules;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * A-04…A-10 and A-11…A-18 (DOC-44 §3.3, DOC-49 §9) for db.
 *
 * <p>The module predates Clean Architecture (DR-104), so the violations of A-11…A-18 that exist today are frozen in
 * {@code src/test/resources/archunit_store}; the store may only shrink (DOC-49 §9.2). Any violation that is not in
 * the store fails, wherever it is. On top of that, packages that are not in {@link #LEGACY_PACKAGES} must comply
 * immediately and are checked without the freeze, so a store refreeze can never hide a violation there.
 */
class ArchitectureTest {

    private static final String APP = "db";

    /** Top-level packages of {@code dev.pti.db} at the time of the freeze (P4-18). New packages are not listed. */
    private static final Set<String> LEGACY_PACKAGES = Set.of();

    /** Rules of A-01…A-10 that the module violated when they were built, so they are frozen like A-11…A-18. */
    private static final Set<String> FROZEN_BOUNDARY_RULES = Set.of();

    private static final JavaClasses PRODUCTION = PtiArchitectureRules.productionClasses(APP);

    @TestFactory
    @DisplayName("Module boundaries and coding rules")
    Stream<DynamicTest> boundaryAndCodingRules() {
        return PtiArchitectureRules.boundaryAndCodingRules(APP).entrySet().stream()
                .map(entry ->
                        dynamicTest(entry.getValue().getDescription(), () -> check(entry.getKey(), entry.getValue())));
    }

    private static void check(String id, ArchRule rule) {
        if (FROZEN_BOUNDARY_RULES.contains(id)) {
            PtiArchitectureRules.checkFrozen(rule, PRODUCTION);
        } else {
            rule.check(PRODUCTION);
        }
    }

    @TestFactory
    @DisplayName("A-11…A-18 Clean Architecture, legacy violations frozen")
    Stream<DynamicTest> cleanArchitectureRulesFrozen() {
        return PtiArchitectureRules.cleanArchitectureRules(APP).values().stream()
                .map(rule ->
                        dynamicTest(rule.getDescription(), () -> PtiArchitectureRules.checkFrozen(rule, PRODUCTION)));
    }

    @TestFactory
    @DisplayName("A-11…A-18 Clean Architecture, new packages not frozen")
    Stream<DynamicTest> cleanArchitectureRulesInNewPackages() {
        JavaClasses newClasses = PtiArchitectureRules.newClasses(PRODUCTION, APP, LEGACY_PACKAGES);
        return PtiArchitectureRules.cleanArchitectureRules(APP).values().stream()
                .map(rule -> dynamicTest(rule.getDescription(), () -> rule.check(newClasses)));
    }

    @Test
    @DisplayName("A-09 test code does not use Thread.sleep")
    void testCodeDoesNotSleep() {
        PtiArchitectureRules.a09NoThreadSleepInTests().check(PtiArchitectureRules.testClasses());
    }
}
