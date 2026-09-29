package dev.pti.simulator.scenario;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes one parameter of a scenario for the catalog of {@code GET /sim/scenarios} (DOC-25 §8). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ScenarioParam {

    /** The English label of the form field. */
    String label();

    ParamType type() default ParamType.AUTO;

    /** Allowed values of an {@code ENUM} whose Java type is not an enum, e.g. {@code directionId}. */
    String[] options() default {};

    /** {@code null} is a meaningful value, e.g. both directions. */
    boolean nullable() default false;

    /** The catalog lists up to 20 active sale points as suggestions. */
    boolean salePointSuggestions() default false;
}
