package dev.pti.api.platform.adapter.out.jdbc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository that writes through the {@code operator} datasource ({@code replay_operator}, DR-20, DOC-31
 * §10.1). Architecture rule A-03 lets only classes with this annotation inject that datasource
 * ({@code @Qualifier("operator")}), so a write can never go through the read-only {@code reader} by accident and a
 * reviewer finds every writer by searching for the annotation.
 *
 * <p>Other features may name this type without breaking A-14; it is a marker, not a dependency on the platform
 * feature's internals.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OperatorRepository {}
