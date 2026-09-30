package dev.pti.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Probes for A-14 and A-15 with synthetic classes (package {@code dev.pti.archprobe}, test sources only): the rule
 * opens exactly the two dependencies on {@code platform} that DOC-49 §9.1 names, and nothing else. A probe app stands in
 * for {@code dev.pti.api}; the rules take the app name, so {@code archprobe.mixed} is an app with the features
 * {@code transit}, {@code insight} and {@code platform}.
 */
class PtiArchitectureRulesProbeTest {

    private static final String MIXED = "archprobe.mixed";
    private static final String LEAK = "archprobe.leak";

    private static final JavaClasses MIXED_CLASSES = new ClassFileImporter().importPackages("dev.pti.archprobe.mixed");
    private static final JavaClasses LEAK_CLASSES = new ClassFileImporter().importPackages("dev.pti.archprobe.leak");

    @Test
    @DisplayName("A-14 lets a feature's inbound adapter use the platform's inbound adapter and domain")
    void inboundAdapterMayUsePlatformInbound() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).doesNotContain("AllowedController");
    }

    @Test
    @DisplayName("A-14 lets a feature's outbound adapter use the platform's outbound adapter and domain")
    void outboundAdapterMayUsePlatformOutbound() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).doesNotContain("AllowedRepository");
    }

    @Test
    @DisplayName("A-14 still lets any layer use the platform's domain")
    void anyLayerMayUsePlatformDomain() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).doesNotContain("AllowedUseCase");
    }

    @Test
    @DisplayName("A-14 keeps an inbound adapter away from the platform's outbound adapter")
    void inboundAdapterMayNotUsePlatformOutbound() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).contains("BadWebToPlatformOut");
    }

    @Test
    @DisplayName("A-14 keeps an outbound adapter away from the platform's inbound adapter")
    void outboundAdapterMayNotUsePlatformInbound() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).contains("BadOutToPlatformIn");
    }

    @Test
    @DisplayName("A-14 keeps the platform's config closed")
    void platformConfigStaysClosed() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).contains("BadWebToPlatformConfig");
    }

    @Test
    @DisplayName("A-14 keeps application code away from the platform's adapters")
    void applicationMayNotUsePlatformAdapters() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).contains("BadUseCaseToPlatformAdapter");
    }

    @Test
    @DisplayName("A-14 does not open the adapters of ordinary features")
    void otherFeaturesStayClosed() {
        assertThat(a14Offenders(MIXED, MIXED_CLASSES)).contains("BadWebToOtherFeature");
    }

    @Test
    @DisplayName("A-14 forbids the platform to depend on another feature")
    void platformMayNotDependOnAFeature() {
        assertThat(a14Offenders(LEAK, LEAK_CLASSES)).contains("LeakyHelper");
    }

    @Test
    @DisplayName("A-15 still forbids transit.adapter.in.web -> platform.adapter.out.jdbc")
    void inboundToOutboundStaysRedForA15() {
        ArchRule rule = PtiArchitectureRules.a15InboundAdaptersDoNotCallOutboundAdapters(MIXED);

        assertThat(offenders(rule, MIXED_CLASSES))
                .contains("BadWebToPlatformOut")
                .doesNotContain("AllowedController");
    }

    private static String a14Offenders(String app, JavaClasses classes) {
        return offenders(PtiArchitectureRules.a14FeaturesAreIsolated(app), classes);
    }

    private static String offenders(ArchRule rule, JavaClasses classes) {
        List<String> details = rule.evaluate(classes).getFailureReport().getDetails();
        return String.join("\n", details);
    }
}
