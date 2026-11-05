package dev.pti.common;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.tngtech.archunit.core.domain.JavaClasses;
import dev.pti.testing.PtiArchitectureRules;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * A-01, A-04…A-10 and the shared-kernel rule (DOC-44 §3.3, DOC-49 §4.2, §9) for common.
 *
 * <p>{@code common} is a flat library (D-R1, DR-115), so the layer rules A-11…A-18 do not apply to it. What binds it
 * instead is that the kernel packages stay plain Java, and that {@code domain} and {@code application} of every app
 * use only the kernel (A-13, checked in each app).
 */
class ArchitectureTest {

    private static final String APP = "common";

    private static final JavaClasses PRODUCTION = PtiArchitectureRules.productionClasses(APP);

    @TestFactory
    @DisplayName("Module boundaries and coding rules")
    Stream<DynamicTest> boundaryAndCodingRules() {
        return PtiArchitectureRules.boundaryAndCodingRules(APP).entrySet().stream()
                .map(entry -> dynamicTest(
                        entry.getValue().getDescription(),
                        () -> entry.getValue().check(PRODUCTION)));
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
