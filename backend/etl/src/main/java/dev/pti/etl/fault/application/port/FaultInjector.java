package dev.pti.etl.fault.application.port;

import dev.pti.etl.fault.domain.FaultPoint;

/** A hook at each {@link FaultPoint}; a no-op in production (DOC-19 §8). */
@FunctionalInterface
public interface FaultInjector {

    FaultInjector NOOP = point -> {};

    void hit(FaultPoint point);
}
