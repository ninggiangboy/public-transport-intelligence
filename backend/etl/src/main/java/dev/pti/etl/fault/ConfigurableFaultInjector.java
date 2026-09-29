package dev.pti.etl.fault;

import dev.pti.common.error.FatalException;
import dev.pti.common.error.TransientInfraException;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Fault injection for the {@code test} and {@code experiment} profiles (DOC-19 §8). Reads
 * {@code pti.test.fault.<point>=<action>}, {@code pti.test.fault.after-n} (hits to let through first, default 0) and
 * {@code pti.test.fault.times} (how many hits fail, default 1). Tests can also arm points directly.
 */
public final class ConfigurableFaultInjector implements FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableFaultInjector.class);

    private final Map<FaultPoint, Arm> arms = new EnumMap<>(FaultPoint.class);
    private final IntConsumer halt;
    private final Object lock = new Object();

    public ConfigurableFaultInjector(IntConsumer halt) {
        this.halt = halt;
    }

    public static ConfigurableFaultInjector fromEnvironment(Environment env) {
        ConfigurableFaultInjector injector = new ConfigurableFaultInjector(Runtime.getRuntime()::halt);
        int afterN = env.getProperty("pti.test.fault.after-n", Integer.class, 0);
        int times = env.getProperty("pti.test.fault.times", Integer.class, 1);
        for (FaultPoint point : FaultPoint.values()) {
            String action = env.getProperty("pti.test.fault." + point.key());
            if (action != null && !action.isBlank()) {
                injector.arm(point, FaultAction.parse(action), afterN, times);
            }
        }
        return injector;
    }

    /** Fails {@code times} hits of {@code point} after letting {@code afterN} hits through. */
    public void arm(FaultPoint point, FaultAction action, int afterN, int times) {
        synchronized (lock) {
            arms.put(point, new Arm(action, afterN, times));
        }
    }

    public void disarmAll() {
        synchronized (lock) {
            arms.clear();
        }
    }

    @Override
    public void hit(FaultPoint point) {
        FaultAction action;
        synchronized (lock) {
            Arm arm = arms.get(point);
            if (arm == null || !arm.fire()) {
                return;
            }
            action = arm.action;
        }
        log.warn("Injecting {} at {}", action, point);
        switch (action) {
            case THROW_TRANSIENT -> throw new TransientInfraException("Injected transient fault at " + point, null);
            case THROW_FATAL -> throw new FatalException("Injected fatal fault at " + point);
            case HALT -> halt.accept(137);
            default -> throw new IllegalStateException("Unknown fault action " + action);
        }
    }

    private static final class Arm {
        private final FaultAction action;
        private int skip;
        private int remaining;

        Arm(FaultAction action, int skip, int remaining) {
            this.action = action;
            this.skip = skip;
            this.remaining = remaining;
        }

        boolean fire() {
            if (skip > 0) {
                skip--;
                return false;
            }
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}
