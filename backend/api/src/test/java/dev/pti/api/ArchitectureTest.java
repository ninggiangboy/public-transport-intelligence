package dev.pti.api;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.tngtech.archunit.core.domain.JavaClasses;
import dev.pti.testing.PtiArchitectureRules;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** A-03, A-04…A-10 and A-11…A-18 (DOC-44 §3.3, DOC-49 §9) for the API. A-11…A-18 apply directly: no freeze. */
class ArchitectureTest {

    private static final String APP = "api";

    private static final JavaClasses PRODUCTION = PtiArchitectureRules.productionClasses(APP);

    @TestFactory
    @DisplayName("A-03, A-04…A-08, A-10 module boundaries and coding rules")
    Stream<DynamicTest> boundaryAndCodingRules() {
        return PtiArchitectureRules.boundaryAndCodingRules(APP).values().stream()
                .map(rule -> dynamicTest(rule.getDescription(), () -> rule.check(PRODUCTION)));
    }

    @TestFactory
    @DisplayName("A-11…A-18 Clean Architecture")
    Stream<DynamicTest> cleanArchitectureRules() {
        return PtiArchitectureRules.cleanArchitectureRules(APP).values().stream()
                .map(rule -> dynamicTest(rule.getDescription(), () -> rule.check(PRODUCTION)));
    }

    @Test
    @DisplayName("A-09 test code does not use Thread.sleep")
    void testCodeDoesNotSleep() {
        PtiArchitectureRules.a09NoThreadSleepInTests().check(PtiArchitectureRules.testClasses());
    }
}
