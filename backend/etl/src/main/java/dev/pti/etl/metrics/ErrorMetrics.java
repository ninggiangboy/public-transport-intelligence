package dev.pti.etl.metrics;

import dev.pti.common.error.ErrorKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * {@code pti_errors_total{kind, type}} (DOC-30 §5): one count per error handled, where it is handled. Data errors are
 * counted when their dead letter commits, infrastructure and fatal errors when a poll fails. {@code type} is the short
 * class name of the exception, a small fixed set.
 */
public final class ErrorMetrics {

    private ErrorMetrics() {}

    public static Counter counter(MeterRegistry meters, ErrorKind kind, String errorClass) {
        return Counter.builder("pti.errors")
                .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                .tag("type", shortName(errorClass))
                .register(meters);
    }

    public static void record(MeterRegistry meters, ErrorKind kind, Throwable error) {
        counter(meters, kind, error.getClass().getName()).increment();
    }

    static String shortName(String className) {
        int dollar = className.lastIndexOf('$');
        int dot = className.lastIndexOf('.');
        return className.substring(Math.max(dollar, dot) + 1);
    }
}
