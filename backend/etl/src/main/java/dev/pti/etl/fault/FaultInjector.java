package dev.pti.etl.fault;

/** A hook at each {@link FaultPoint}; a no-op in production (DOC-19 §8). */
@FunctionalInterface
public interface FaultInjector {

    FaultInjector NOOP = point -> {};

    void hit(FaultPoint point);
}
