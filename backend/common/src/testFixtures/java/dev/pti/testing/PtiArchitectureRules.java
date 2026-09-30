package dev.pti.testing;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.importer.Locations;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import dev.pti.common.error.PtiException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The ArchUnit rules of the backend (DOC-44 §3.3, DOC-49 §9). {@code A-01}…{@code A-10} guard module boundaries and
 * coding rules, {@code A-11}…{@code A-18} guard the Clean Architecture layout of DOC-49. Each module calls the rules
 * from one architecture test in its own {@code src/test}; modules that predate Clean Architecture (DR-104) wrap
 * {@code A-11}…{@code A-18} in {@link FreezingArchRule} until Phase R.
 *
 * <p>Every rule allows an empty subject, because {@code analytics}, {@code api} and {@code triage-worker} start with
 * (almost) no classes; a rule bites as soon as a class it covers exists. The closed list of exceptions of DOC-49 §10
 * is coded once, in {@link #isX01Exempt}.
 */
public final class PtiArchitectureRules {

    /** The deployable apps; they are independent of each other and share only {@code common} and {@code analytics}. */
    private static final List<String> APPS = List.of("etl", "api", "triage", "simulator");

    /** What {@code domain} and {@code application} must not touch (DOC-49 §4.1, A-12). */
    private static final String[] INFRASTRUCTURE_PACKAGES = {
        "org.springframework..",
        "jakarta..",
        "tools.jackson..",
        "com.fasterxml.jackson..",
        "io.micrometer..",
        "io.opentelemetry..",
        "org.apache.kafka..",
        "software.amazon..",
        "io.github.resilience4j..",
        "com.github.benmanes.caffeine..",
        "net.javacrumbs.shedlock..",
        "org.springaicommunity..",
        "com.bucket4j..",
        "java.sql..",
        "javax.sql.."
    };

    /** The cross-cutting feature of an app (DOC-49 §3). */
    private static final String PLATFORM = "platform";

    private static final String COMMON_PACKAGE = "dev.pti.common";
    private static final String PII_PACKAGE = "dev.pti.common.pii..";

    /** Packages of {@code common} that {@code domain} and {@code application} may use whole (DOC-49 §4.2, A-13). */
    private static final List<String> SHARED_KERNEL_PACKAGES = List.of(
            "dev.pti.common.time",
            "dev.pti.common.error",
            "dev.pti.common.dq",
            "dev.pti.common.tx",
            "dev.pti.common.id",
            "dev.pti.common.events");

    /** Kernel types that live in a package which is otherwise not in the kernel, or are excluded from one that is. */
    private static final String GTFS_TIME = "dev.pti.common.gtfs.GtfsTime";

    private static final String ERROR_CLASSIFIER = "dev.pti.common.error.ErrorClassifier";

    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";
    private static final String BEAN = "org.springframework.context.annotation.Bean";
    private static final String PROFILE = "org.springframework.context.annotation.Profile";
    private static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String OPERATOR_REPOSITORY = "dev.pti.api.platform.adapter.out.jdbc.OperatorRepository";
    private static final String CONFIGURABLE_FAULT_INJECTOR = "dev.pti.etl.fault.ConfigurableFaultInjector";

    /**
     * Test code: the class directories of the test source sets and the test fixtures jar. Gradle puts a module's own
     * main classes on its test classpath as a jar, so jars cannot be excluded wholesale to find production classes.
     */
    private static final Pattern TEST_CODE = Pattern.compile(
            ".*(/classes/java/(test|testFixtures|integrationTest|contractTest)/|-test-fixtures\\.jar).*");

    /** System property with extra test class directories (integrationTest, contractTest), see pti.java-conventions. */
    public static final String EXTRA_TEST_CLASSES_PROPERTY = "pti.arch.extra-test-classes";

    private PtiArchitectureRules() {}

    // ---------------------------------------------------------------------------------------------------------
    // Importing classes
    // ---------------------------------------------------------------------------------------------------------

    /** Production classes of {@code dev.pti.<app>} (no test or test fixture classes). */
    public static JavaClasses productionClasses(String app) {
        return new ClassFileImporter()
                .withImportOption(location -> !location.matches(TEST_CODE))
                .importPackages(appPackage(app));
    }

    /**
     * Test code visible to the running test JVM: the unit tests and test fixtures of the module, plus the class
     * directories of its other test suites that the build passes in {@value #EXTRA_TEST_CLASSES_PROPERTY}.
     */
    public static JavaClasses testClasses() {
        List<Location> locations = new ArrayList<>(Locations.ofPackage("dev.pti"));
        String extra = System.getProperty(EXTRA_TEST_CLASSES_PROPERTY, "");
        List<URL> extraDirectories = new ArrayList<>();
        for (String directory : extra.split(File.pathSeparator)) {
            if (!directory.isBlank() && new File(directory).isDirectory()) {
                try {
                    extraDirectories.add(Path.of(directory).toUri().toURL());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        locations.addAll(Locations.of(extraDirectories));
        return new ClassFileImporter()
                .withImportOption(location -> location.matches(TEST_CODE))
                .importLocations(locations);
    }

    /**
     * The classes of a module that predates Clean Architecture: the ones in its legacy top-level packages and in its
     * root package. Their {@code A-11}…{@code A-18} violations are frozen.
     */
    public static JavaClasses legacyClasses(JavaClasses production, String app, Collection<String> legacyPackages) {
        return production.that(legacy(app, legacyPackages));
    }

    /**
     * The classes of a module that predates Clean Architecture but are not legacy: every package that is not in
     * {@code legacyPackages} (for example a future {@code dev.pti.etl.analytics}). They comply immediately (DOC-49
     * §1), so their rules are never frozen. Classes in the root package are left to the freeze.
     */
    public static JavaClasses newClasses(JavaClasses production, String app, Collection<String> legacyPackages) {
        return production.that(DescribedPredicate.describe(
                "are in a package of " + appPackage(app) + " that is not legacy",
                javaClass -> !legacy(app, legacyPackages).test(javaClass)
                        && !javaClass.getPackageName().equals(appPackage(app))));
    }

    private static DescribedPredicate<JavaClass> legacy(String app, Collection<String> legacyPackages) {
        String root = appPackage(app);
        return DescribedPredicate.describe("are legacy classes of " + root, javaClass -> {
            String packageName = javaClass.getPackageName();
            if (packageName.equals(root)) {
                return true;
            }
            String relative = packageName.substring(root.length() + 1);
            int dot = relative.indexOf('.');
            return legacyPackages.contains(dot < 0 ? relative : relative.substring(0, dot));
        });
    }

    /** Checks the rule with its violations frozen in the module's {@code archunit_store} (DOC-49 §9.2). */
    public static void checkFrozen(ArchRule rule, JavaClasses classes) {
        FreezingArchRule.freeze(rule).check(classes);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rule sets
    // ---------------------------------------------------------------------------------------------------------

    /**
     * {@code A-01}…{@code A-08} and {@code A-10} for the production classes of one module, keyed by rule id. A-01 only
     * applies to {@code common}, A-02 to {@code analytics} and A-03 to {@code api}. {@code A-09} is about test code:
     * see {@link #a09NoThreadSleepInTests()}.
     */
    public static Map<String, ArchRule> boundaryAndCodingRules(String app) {
        Map<String, ArchRule> rules = new LinkedHashMap<>();
        if (app.equals("common")) {
            rules.put("A-01", a01CommonAvoidsKafkaBatchAndWeb());
        }
        if (app.equals("analytics")) {
            rules.put("A-02", a02AnalyticsIsIndependent());
        }
        if (app.equals("api")) {
            rules.put("A-03", a03OnlyOperatorRepositoriesUseTheOperatorDataSource());
        }
        rules.put("A-04", a04AppsAreIndependent(app));
        rules.put("A-05", a05PackagesAreByFeature());
        rules.put("A-06", a06NoStandardStreamsOrJavaUtilLogging());
        rules.put("A-07", a07NoSystemClock());
        rules.put("A-08", a08ExceptionsExtendPtiException());
        rules.put("A-10", a10NoFaultInjectorInProduction());
        return rules;
    }

    /** {@code A-11}…{@code A-18} (DOC-49 §9.1) for {@code dev.pti.<app>}, keyed by rule id. */
    public static Map<String, ArchRule> cleanArchitectureRules(String app) {
        Map<String, ArchRule> rules = new LinkedHashMap<>();
        rules.put("A-11", a11Layering(app));
        rules.put("A-12", a12DomainAndApplicationAreFrameworkFree(app));
        rules.put("A-13", a13OnlySharedKernelOfCommon(app));
        rules.put("A-14", a14FeaturesAreIsolated(app));
        rules.put("A-15", a15InboundAdaptersDoNotCallOutboundAdapters(app));
        rules.put("A-16", a16SpringConfigurationOnlyInConfig(app));
        rules.put("A-17", a17PortsAreImplementedInAdapters(app));
        rules.put("A-18", a18EveryClassBelongsToALayer(app));
        return rules;
    }

    // ---------------------------------------------------------------------------------------------------------
    // A-01 … A-10
    // ---------------------------------------------------------------------------------------------------------

    /** A-01: {@code common} does not depend on Spring Kafka, Spring Batch or Spring Web. */
    public static ArchRule a01CommonAvoidsKafkaBatchAndWeb() {
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(COMMON_PACKAGE + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.kafka..", "org.springframework.batch..", "org.springframework.web..")
                .as("A-01 common does not depend on Spring Kafka, Spring Batch or Spring Web")
                .allowEmptyShould(true);
    }

    /** A-02: {@code analytics} does not depend on {@code etl} or {@code api} and does not use {@code KafkaTemplate}. */
    public static ArchRule a02AnalyticsIsIndependent() {
        return CompositeArchRule.of(ArchRuleDefinition.noClasses()
                        .that()
                        .resideInAPackage("dev.pti.analytics..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("dev.pti.etl..", "dev.pti.api..")
                        .allowEmptyShould(true))
                .and(ArchRuleDefinition.noClasses()
                        .that()
                        .resideInAPackage("dev.pti.analytics..")
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                        .allowEmptyShould(true))
                .as("A-02 analytics does not depend on etl or api and does not use KafkaTemplate");
    }

    /**
     * A-03: only a class annotated {@code @OperatorRepository} injects the {@code operator} datasource
     * ({@code @Qualifier("operator")} on a field or on a constructor or method parameter). Wiring in
     * {@code ..config..} is exempt: that is where the datasource beans and their transaction runners are built.
     */
    public static ArchRule a03OnlyOperatorRepositoriesUseTheOperatorDataSource() {
        ArchCondition<JavaClass> condition =
                new ArchCondition<>("be annotated with @OperatorRepository when they inject the operator datasource") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (item.isAnnotatedWith(OPERATOR_REPOSITORY)) {
                            return;
                        }
                        for (JavaField field : item.getFields()) {
                            if (hasQualifier(field, "operator")) {
                                events.add(SimpleConditionEvent.violated(
                                        field,
                                        field.getDescription() + " injects the operator datasource without "
                                                + "@OperatorRepository in " + field.getSourceCodeLocation()));
                            }
                        }
                        for (JavaCodeUnit unit : item.getCodeUnits()) {
                            for (JavaParameter parameter : unit.getParameters()) {
                                if (hasQualifier(parameter, "operator")) {
                                    events.add(SimpleConditionEvent.violated(
                                            unit,
                                            unit.getDescription() + " injects the operator datasource without "
                                                    + "@OperatorRepository in " + unit.getSourceCodeLocation()));
                                }
                            }
                        }
                    }
                };
        return ArchRuleDefinition.classes()
                .that()
                .resideInAPackage("dev.pti.api..")
                .and()
                .resideOutsideOfPackage("..config..")
                .should(condition)
                .as("A-03 only @OperatorRepository classes inject the operator datasource")
                .allowEmptyShould(true);
    }

    private static boolean hasQualifier(HasAnnotations<?> element, String value) {
        return element.tryGetAnnotationOfType(QUALIFIER)
                .flatMap(annotation -> annotation.get("value"))
                .map(value::equals)
                .orElse(false);
    }

    /** A-04: an app depends on no other app; only {@code common} and {@code analytics} are shared. */
    public static ArchRule a04AppsAreIndependent(String app) {
        String[] otherApps = APPS.stream()
                .filter(other -> !other.equals(app))
                .map(PtiArchitectureRules::appPackage)
                .map(packageName -> packageName + "..")
                .toArray(String[]::new);
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(appPackage(app) + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(otherApps)
                .as("A-04 " + app + " does not depend on another app")
                .allowEmptyShould(true);
    }

    /** A-05: packages are by feature, so no {@code controller}, {@code service}, {@code repository}, {@code dto}. */
    public static ArchRule a05PackagesAreByFeature() {
        return ArchRuleDefinition.noClasses()
                .should()
                .resideInAnyPackage(
                        "dev.pti.*.controller..", "dev.pti.*.service..", "dev.pti.*.repository..", "dev.pti.*.dto..")
                .as("A-05 packages are by feature: no controller, service, repository or dto package at level one")
                .allowEmptyShould(true);
    }

    /** A-06: no {@code System.out}, {@code System.err}, {@code printStackTrace} or {@code java.util.logging}. */
    public static ArchRule a06NoStandardStreamsOrJavaUtilLogging() {
        return CompositeArchRule.of(GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true))
                .and(GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.allowEmptyShould(true))
                .as("A-06 no System.out, System.err, printStackTrace or java.util.logging");
    }

    /**
     * A-07: no {@code LocalDateTime.now()}, {@code Instant.now()} or {@code Clock.systemUTC()} except in
     * {@code BusinessClock} and {@code SystemClockConfig} (DR-67).
     */
    public static ArchRule a07NoSystemClock() {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not read the system clock") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (isClockOwner(item)) {
                    return;
                }
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    if (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && target.getRawParameterTypes().isEmpty()
                            && isSystemClockRead(target)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
        return ArchRuleDefinition.classes()
                .should(condition)
                .as("A-07 no LocalDateTime.now(), Instant.now() or Clock.systemUTC() outside BusinessClock "
                        + "and SystemClockConfig")
                .allowEmptyShould(true);
    }

    private static boolean isClockOwner(JavaClass javaClass) {
        String name = topLevelName(javaClass);
        return name.equals("dev.pti.common.time.BusinessClock") || name.endsWith(".SystemClockConfig");
    }

    private static boolean isSystemClockRead(AccessTarget.CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String method = target.getName();
        return (method.equals("now")
                        && (owner.isEquivalentTo(Instant.class) || owner.isEquivalentTo(LocalDateTime.class)))
                || (method.equals("systemUTC") && owner.isEquivalentTo(Clock.class));
    }

    /**
     * A-08: the exceptions that project code throws extend {@code PtiException}, except
     * {@code IllegalArgumentException} and {@code IllegalStateException} raised by constructors and guards. Checked
     * on two sides: exception classes declared in {@code dev.pti} extend it, and project code only instantiates
     * exceptions that do (or those two). {@code MatchException}, which the compiler generates, is not counted.
     */
    public static ArchRule a08ExceptionsExtendPtiException() {
        ArchCondition<JavaClass> declared = new ArchCondition<>("extend PtiException") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isAssignableTo(PtiException.class)) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            item.getDescription() + " is an exception that does not extend PtiException in "
                                    + item.getSourceCodeLocation()));
                }
            }
        };
        ArchCondition<JavaClass> instantiated = new ArchCondition<>("only create PtiException or guard exceptions") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (var call : item.getConstructorCallsFromSelf()) {
                    JavaClass created = call.getTargetOwner();
                    // super(...) of an exception class is its declaration, which the first condition covers.
                    boolean superCall = call.getOrigin().isConstructor()
                            && item.getRawSuperclass().filter(created::equals).isPresent();
                    // The compiler throws MatchException for an exhaustive switch over a sealed type.
                    if (!superCall
                            && !created.isEquivalentTo(MatchException.class)
                            && created.isAssignableTo(Throwable.class)
                            && !created.isAssignableTo(PtiException.class)
                            && !created.isAssignableTo(IllegalArgumentException.class)
                            && !created.isAssignableTo(IllegalStateException.class)) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
        return CompositeArchRule.of(ArchRuleDefinition.classes()
                        .that()
                        .areAssignableTo(Throwable.class)
                        .and()
                        .resideInAPackage("dev.pti..")
                        .should(declared)
                        .allowEmptyShould(true))
                .and(ArchRuleDefinition.classes().should(instantiated).allowEmptyShould(true))
                .as("A-08 exceptions thrown by project code extend PtiException "
                        + "(IllegalArgumentException and IllegalStateException allowed in guards)");
    }

    /** A-09: test code does not call {@code Thread.sleep}; tests wait with Awaitility. */
    public static ArchRule a09NoThreadSleepInTests() {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not call Thread.sleep") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    if (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && target.getName().equals("sleep")
                            && target.getOwner().isEquivalentTo(Thread.class)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
        return ArchRuleDefinition.classes()
                .should(condition)
                .as("A-09 test code does not use Thread.sleep")
                .allowEmptyShould(true);
    }

    /**
     * A-10: no production class depends on {@code ConfigurableFaultInjector}, except beans whose method or class
     * carries {@code @Profile} with {@code test} or {@code experiment} (DOC-19 §8).
     */
    public static ArchRule a10NoFaultInjectorInProduction() {
        ArchCondition<JavaClass> condition =
                new ArchCondition<>("not depend on ConfigurableFaultInjector outside a test or experiment profile") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (topLevelName(item).equals(CONFIGURABLE_FAULT_INJECTOR) || profileGated(item)) {
                            return;
                        }
                        boolean dependsOn = item.getDirectDependenciesFromSelf().stream()
                                .anyMatch(dependency ->
                                        dependency.getTargetClass().getName().equals(CONFIGURABLE_FAULT_INJECTOR));
                        if (!dependsOn) {
                            return;
                        }
                        boolean explained = false;
                        for (JavaField field : item.getFields()) {
                            if (field.getRawType().getName().equals(CONFIGURABLE_FAULT_INJECTOR)) {
                                explained = true;
                                events.add(SimpleConditionEvent.violated(
                                        field,
                                        field.getDescription() + " is not profile-gated in "
                                                + field.getSourceCodeLocation()));
                            }
                        }
                        for (JavaCodeUnit unit : item.getCodeUnits()) {
                            if (usesFaultInjector(unit)) {
                                explained = true;
                                if (!profileGated(unit)) {
                                    events.add(SimpleConditionEvent.violated(
                                            unit,
                                            unit.getDescription() + " is not profile-gated in "
                                                    + unit.getSourceCodeLocation()));
                                }
                            }
                        }
                        if (!explained) {
                            events.add(SimpleConditionEvent.violated(
                                    item,
                                    item.getDescription() + " depends on ConfigurableFaultInjector in "
                                            + item.getSourceCodeLocation()));
                        }
                    }
                };
        return ArchRuleDefinition.classes()
                .should(condition)
                .as("A-10 production code depends on ConfigurableFaultInjector only in test or experiment profiles")
                .allowEmptyShould(true);
    }

    private static boolean usesFaultInjector(JavaCodeUnit unit) {
        if (unit.getRawReturnType().getName().equals(CONFIGURABLE_FAULT_INJECTOR)
                || unit.getRawParameterTypes().stream()
                        .anyMatch(type -> type.getName().equals(CONFIGURABLE_FAULT_INJECTOR))) {
            return true;
        }
        return unit.getAccessesFromSelf().stream()
                .anyMatch(access -> access.getTargetOwner().getName().equals(CONFIGURABLE_FAULT_INJECTOR));
    }

    private static boolean profileGated(HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(PROFILE)
                .flatMap(annotation -> annotation.get("value"))
                .map(value -> value instanceof Object[] profiles
                        && Arrays.stream(profiles)
                                .map(Object::toString)
                                .anyMatch(profile -> profile.contains("test") || profile.contains("experiment")))
                .orElse(false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // A-11 … A-18
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A-11: layering per feature. {@code domain} depends on no other layer, {@code application} not on
     * {@code adapter} or {@code config}, {@code adapter} not on {@code config} except on {@code @ConfigurationProperties}
     * classes.
     */
    public static ArchRule a11Layering(String app) {
        return dependencyRule(
                "A-11 layering: domain <- application <- adapter, config is wired from outside",
                app,
                (origin, target) -> {
                    Place from = placeOf(origin, app);
                    Place to = placeOf(target, app);
                    if (from == null || to == null) {
                        return false;
                    }
                    return switch (from.layer()) {
                        case DOMAIN -> to.layer() != Layer.DOMAIN;
                        case APPLICATION -> to.layer() == Layer.ADAPTER || to.layer() == Layer.CONFIG;
                        case ADAPTER -> to.layer() == Layer.CONFIG && !isConfigurationProperties(target);
                        case CONFIG -> false;
                    };
                });
    }

    /**
     * A-12: {@code domain} and {@code application} do not depend on frameworks or infrastructure (DOC-49 §4.1),
     * except X-01.
     */
    public static ArchRule a12DomainAndApplicationAreFrameworkFree(String app) {
        DescribedPredicate<JavaClass> infrastructure = JavaClass.Predicates.resideInAnyPackage(INFRASTRUCTURE_PACKAGES);
        return dependencyRule(
                "A-12 domain and application depend on no framework or infrastructure",
                app,
                (origin, target) -> isDomainOrApplication(origin, app)
                        && infrastructure.test(target)
                        && !isX01Exempt(origin, target));
    }

    /**
     * A-13: {@code domain} and {@code application} use {@code dev.pti.common} only through the shared kernel
     * (DOC-49 §4.2), except X-01.
     */
    public static ArchRule a13OnlySharedKernelOfCommon(String app) {
        return dependencyRule(
                "A-13 domain and application use common only through the shared kernel",
                app,
                (origin, target) -> isDomainOrApplication(origin, app)
                        && isCommon(target)
                        && !isSharedKernel(target)
                        && !isX01Exempt(origin, target));
    }

    /**
     * A-14: a feature does not use the {@code adapter} or {@code config} of another feature, and features form no
     * cycle. A dependency on an annotation type of {@code platform} is allowed, which is how {@code
     * @OperatorRepository} is shared.
     *
     * <p>{@code platform} is cross-cutting infrastructure (DOC-49 §3), so two more dependencies on it are allowed: a
     * class in {@code <feature>.adapter.in..} may use {@code platform.adapter.in..} (Problem Details, paging cursors,
     * {@code X-Data-As-Of}) and a class in {@code <feature>.adapter.out..} may use {@code platform.adapter.out..}
     * (caches, SQL loading). Nothing else about {@code platform.adapter} or {@code platform.config} opens up, and
     * {@code platform} itself must not depend on any other feature.
     */
    public static ArchRule a14FeaturesAreIsolated(String app) {
        ArchRule noForeignInternals = dependencyRule(
                "A-14 a feature does not use the adapter or config of another feature", app, (origin, target) -> {
                    Place from = placeOf(origin, app);
                    Place to = placeOf(target, app);
                    if (from == null
                            || to == null
                            || from.feature() == null
                            || to.feature() == null
                            || from.feature().equals(to.feature())) {
                        return false;
                    }
                    if (from.feature().equals(PLATFORM)) {
                        return true;
                    }
                    boolean sharedAnnotation =
                            target.isAnnotation() && to.feature().equals(PLATFORM);
                    return (to.layer() == Layer.ADAPTER || to.layer() == Layer.CONFIG)
                            && !sharedAnnotation
                            && !isPlatformAdapterOfSameDirection(origin, from, target, to, app);
                });
        SliceAssignment features = new SliceAssignment() {
            @Override
            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                String feature = featureOf(javaClass, app);
                return feature == null ? SliceIdentifier.ignore() : SliceIdentifier.of(feature);
            }

            @Override
            public String getDescription() {
                return "features of " + appPackage(app);
            }
        };
        ArchRule noCycles = SlicesRuleDefinition.slices()
                .assignedFrom(features)
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true);
        return CompositeArchRule.of(noForeignInternals)
                .and(noCycles)
                .as("A-14 features do not use each other's adapter or config and form no cycle");
    }

    /** A-15: {@code adapter.in} does not depend on {@code adapter.out}, in any feature. */
    public static ArchRule a15InboundAdaptersDoNotCallOutboundAdapters(String app) {
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(appPackage(app) + "..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(appPackage(app) + "..adapter.out..")
                .as("A-15 adapter.in does not depend on adapter.out")
                .allowEmptyShould(true);
    }

    /** A-16: {@code @Configuration}, {@code @Bean} and {@code @ConfigurationProperties} only in {@code ..config..}. */
    public static ArchRule a16SpringConfigurationOnlyInConfig(String app) {
        String app16 = appPackage(app) + "..";
        return CompositeArchRule.of(ArchRuleDefinition.classes()
                        .that()
                        .resideInAPackage(app16)
                        .and()
                        .areAnnotatedWith(CONFIGURATION)
                        .should()
                        .resideInAPackage("..config..")
                        .allowEmptyShould(true))
                .and(ArchRuleDefinition.classes()
                        .that()
                        .resideInAPackage(app16)
                        .and()
                        .areAnnotatedWith(CONFIGURATION_PROPERTIES)
                        .should()
                        .resideInAPackage("..config..")
                        .allowEmptyShould(true))
                .and(ArchRuleDefinition.methods()
                        .that()
                        .areDeclaredInClassesThat()
                        .resideInAPackage(app16)
                        .and()
                        .areAnnotatedWith(BEAN)
                        .should()
                        .beDeclaredInClassesThat()
                        .resideInAPackage("..config..")
                        .allowEmptyShould(true))
                .as("A-16 @Configuration, @Bean and @ConfigurationProperties only live in config packages");
    }

    /** A-17: production classes that implement an {@code application.port} interface live in an adapter package. */
    public static ArchRule a17PortsAreImplementedInAdapters(String app) {
        String ports = appPackage(app) + "..application.port..";
        String adapters = appPackage(app) + "..adapter..";
        ArchCondition<JavaClass> condition = new ArchCondition<>("be in an adapter package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isInterface()
                        || JavaClass.Predicates.resideInAPackage(adapters).test(item)) {
                    return;
                }
                item.getAllRawInterfaces().stream()
                        .filter(JavaClass.Predicates.resideInAPackage(ports))
                        .forEach(port -> events.add(SimpleConditionEvent.violated(
                                item,
                                item.getDescription() + " implements port " + port.getName()
                                        + " outside an adapter package in " + item.getSourceCodeLocation())));
            }
        };
        return ArchRuleDefinition.classes()
                .that()
                .resideInAPackage(appPackage(app) + "..")
                .should(condition)
                .as("A-17 implementations of application.port interfaces live in adapter packages")
                .allowEmptyShould(true);
    }

    /**
     * A-18: every class below {@code dev.pti.<app>.<feature>} is in {@code domain}, {@code application},
     * {@code adapter} or {@code config}; the root package holds only {@code *Application} classes. The app-level
     * {@code config} package (the composition root of DOC-49 §3) is not a feature.
     */
    public static ArchRule a18EveryClassBelongsToALayer(String app) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("belong to a layer of a feature") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String root = appPackage(app);
                String packageName = item.getPackageName();
                if (packageName.equals(root)) {
                    if (!topLevelName(item).endsWith("Application")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getDescription() + " is in the root package but is not an *Application class in "
                                        + item.getSourceCodeLocation()));
                    }
                    return;
                }
                if (!packageName.startsWith(root + ".")) {
                    return;
                }
                String[] segments = packageName.substring(root.length() + 1).split("\\.");
                if (segments[0].equals("config")) {
                    return;
                }
                if (segments.length < 2 || layerOf(segments[1]) == null) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            item.getDescription() + " is in feature '" + segments[0]
                                    + "' but not in a domain, application, adapter or config package in "
                                    + item.getSourceCodeLocation()));
                }
            }
        };
        return ArchRuleDefinition.classes()
                .that()
                .resideInAPackage(appPackage(app) + "..")
                .should(condition)
                .as("A-18 every class is in a layer of a feature; the root package holds only *Application")
                .allowEmptyShould(true);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shared kernel of common
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The shared kernel of {@code common} (DOC-49 §4.2) is plain Java: none of its classes depends on the frameworks
     * of A-12. Unlike A-12 this covers {@code common} itself, so it is checked directly even while the module's
     * A-11…A-18 are frozen.
     */
    public static ArchRule sharedKernelIsJavaPure() {
        DescribedPredicate<JavaClass> infrastructure = JavaClass.Predicates.resideInAnyPackage(INFRASTRUCTURE_PACKAGES);
        ArchCondition<JavaClass> condition = noDependencyCondition(
                "depend on no framework or infrastructure", (origin, target) -> infrastructure.test(target));
        return ArchRuleDefinition.classes()
                .that(DescribedPredicate.describe(
                        "are in the shared kernel of common", PtiArchitectureRules::isSharedKernel))
                .should(condition)
                .as("The shared kernel of common is plain Java (DOC-49 §4.2)")
                .allowEmptyShould(true);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private enum Layer {
        DOMAIN,
        APPLICATION,
        ADAPTER,
        CONFIG
    }

    /** Where a class sits: its feature ({@code null} for the app-level config package) and layer. */
    private record Place(@Nullable String feature, Layer layer) {}

    private static String appPackage(String app) {
        return "dev.pti." + app;
    }

    private static @Nullable Layer layerOf(String segment) {
        return switch (segment) {
            case "domain" -> Layer.DOMAIN;
            case "application" -> Layer.APPLICATION;
            case "adapter" -> Layer.ADAPTER;
            case "config" -> Layer.CONFIG;
            default -> null;
        };
    }

    private static @Nullable Place placeOf(JavaClass javaClass, String app) {
        String prefix = appPackage(app) + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String[] segments = packageName.substring(prefix.length()).split("\\.");
        if (segments[0].equals("config")) {
            return new Place(null, Layer.CONFIG);
        }
        if (segments.length < 2) {
            return null;
        }
        Layer layer = layerOf(segments[1]);
        return layer == null ? null : new Place(segments[0], layer);
    }

    /**
     * A-14's opening for {@code platform}: an inbound adapter of a feature uses an inbound adapter of the platform, an
     * outbound adapter uses an outbound one. The target must be an adapter class, so {@code platform.config} stays
     * closed, and the two directions never mix.
     */
    private static boolean isPlatformAdapterOfSameDirection(
            JavaClass origin, Place from, JavaClass target, Place to, String app) {
        if (!to.feature().equals(PLATFORM) || from.layer() != Layer.ADAPTER || to.layer() != Layer.ADAPTER) {
            return false;
        }
        String originDirection = adapterDirection(origin, app);
        return originDirection != null && originDirection.equals(adapterDirection(target, app));
    }

    /** {@code in} or {@code out}: the third segment of {@code <feature>.adapter.<direction>}, if the class has one. */
    private static @Nullable String adapterDirection(JavaClass javaClass, String app) {
        String prefix = appPackage(app) + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String[] segments = packageName.substring(prefix.length()).split("\\.");
        return segments.length >= 3 && segments[1].equals("adapter") ? segments[2] : null;
    }

    private static @Nullable String featureOf(JavaClass javaClass, String app) {
        String prefix = appPackage(app) + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String feature = packageName.substring(prefix.length()).split("\\.")[0];
        return feature.equals("config") ? null : feature;
    }

    private static boolean isDomainOrApplication(JavaClass javaClass, String app) {
        Place place = placeOf(javaClass, app);
        return place != null && (place.layer() == Layer.DOMAIN || place.layer() == Layer.APPLICATION);
    }

    private static boolean isCommon(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.equals(COMMON_PACKAGE) || packageName.startsWith(COMMON_PACKAGE + ".");
    }

    /** The kernel of DOC-49 §4.2: time, error (not ErrorClassifier), dq, GtfsTime, tx, id, events. */
    private static boolean isSharedKernel(JavaClass javaClass) {
        String name = topLevelName(javaClass);
        if (name.equals(GTFS_TIME)) {
            return true;
        }
        if (name.equals(ERROR_CLASSIFIER)) {
            return false;
        }
        String packageName = javaClass.getPackageName();
        return SHARED_KERNEL_PACKAGES.stream()
                .anyMatch(kernel -> packageName.equals(kernel) || packageName.startsWith(kernel + "."));
    }

    /**
     * X-01 (DOC-49 §10): the Jackson tree model ({@code JsonNode}, {@code tools.jackson.databind.node..}) and
     * {@code dev.pti.common.pii} in {@code dev.pti.triage..application..}. The only exception coded in a rule; X-02
     * (Spring Batch chunk transactions in {@code etl}) and X-03 ({@code @Scheduled} on {@code adapter.in.scheduling})
     * need no rule change, because neither is a dependency that A-11…A-18 forbid.
     */
    private static boolean isX01Exempt(JavaClass origin, JavaClass target) {
        if (!JavaClass.Predicates.resideInAPackage("dev.pti.triage..application..")
                .test(origin)) {
            return false;
        }
        String name = target.getName();
        return name.equals("tools.jackson.databind.JsonNode")
                || name.startsWith("tools.jackson.databind.JsonNode$")
                || JavaClass.Predicates.resideInAPackage("tools.jackson.databind.node..")
                        .test(target)
                || JavaClass.Predicates.resideInAPackage(PII_PACKAGE).test(target);
    }

    private static boolean isConfigurationProperties(JavaClass javaClass) {
        Optional<JavaClass> current = Optional.of(javaClass);
        while (current.isPresent()) {
            if (current.get().isAnnotatedWith(CONFIGURATION_PROPERTIES)) {
                return true;
            }
            current = current.get().getEnclosingClass();
        }
        return false;
    }

    private static String topLevelName(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        String name = current.getName();
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    private static ArchRule dependencyRule(
            String description, String app, BiPredicate<JavaClass, JavaClass> forbidden) {
        return ArchRuleDefinition.classes()
                .that()
                .resideInAPackage(appPackage(app) + "..")
                .should(noDependencyCondition("have no forbidden dependency", forbidden))
                .as(description)
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaClass> noDependencyCondition(
            String description, BiPredicate<JavaClass, JavaClass> forbidden) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    if (forbidden.test(dependency.getOriginClass(), dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
