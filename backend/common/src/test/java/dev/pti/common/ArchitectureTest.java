package dev.pti.common;

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
 * A-01, A-04…A-10 and A-11…A-18 (DOC-44 §3.3, DOC-49 §9) for common.
 *
 * <p>The module predates Clean Architecture (DR-104), so the violations of A-11…A-18 in its legacy packages are
 * frozen in {@code src/test/resources/archunit_store}; the store may only shrink (DOC-49 §9.2). The packages added in
 * P4-18 are the shared kernel of DOC-49 §4.2 ({@code tx}, {@code id}, {@code events}) and its Spring adapter
 * ({@code spring}). They are flat by design, so the layer rules A-11…A-18 do not apply to them, and they are not in the
 * store. What binds them instead is that the kernel stays plain Java, checked without any freeze.
 */
class ArchitectureTest {

    private static final String APP = "common";

    /** Top-level packages of {@code dev.pti.common} at the time of the freeze (P4-18). */
    private static final Set<String> LEGACY_PACKAGES = Set.of("message", "json", "pii", "gtfs", "time", "error", "dq");

    /** Rules of A-01…A-10 that the module violated when they were built, so they are frozen like A-11…A-18. */
    private static final Set<String> FROZEN_BOUNDARY_RULES = Set.of("A-08");

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
        JavaClasses legacy = PtiArchitectureRules.legacyClasses(PRODUCTION, APP, LEGACY_PACKAGES);
        return PtiArchitectureRules.cleanArchitectureRules(APP).values().stream()
                .map(rule -> dynamicTest(rule.getDescription(), () -> PtiArchitectureRules.checkFrozen(rule, legacy)));
    }

    @Test
    @DisplayName("The shared kernel stays plain Java")
    void sharedKernelIsJavaPure() {
        PtiArchitectureRules.sharedKernelIsJavaPure().check(PRODUCTION);
    }

    @Test
    @DisplayName("A-09 test code does not use Thread.sleep")
    void testCodeDoesNotSleep() {
        PtiArchitectureRules.a09NoThreadSleepInTests().check(PtiArchitectureRules.testClasses());
    }
}
