import org.gradle.api.provider.Property

/** Per-module settings read by the `pti.*` convention plugins. */
abstract class PtiExtension {
    /** Minimum LINE coverage of unit tests (`test`) enforced by `jacocoTestCoverageVerification` (DOC-44 §7). */
    abstract val unitLineCoverage: Property<Double>
}
