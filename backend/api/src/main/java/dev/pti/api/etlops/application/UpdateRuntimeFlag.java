package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.RuntimeFlagStore;
import dev.pti.api.etlops.domain.InvalidFlagValueException;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PUT /etl/flags/{key}} (DOC-32 E-57): sets a runtime flag. The flag must exist (a new flag comes from a
 * migration) and the new value must have the JSON type of the current one. The same value is a 200 that writes
 * nothing. The services pick the change up within five seconds (FR-15.1).
 */
public final class UpdateRuntimeFlag {

    private static final Logger log = LoggerFactory.getLogger(UpdateRuntimeFlag.class);

    private final RuntimeFlagStore store;
    private final TransactionRunner operatorTx;
    private final WriteMetrics metrics;

    public UpdateRuntimeFlag(RuntimeFlagStore store, TransactionRunner operatorTx, WriteMetrics metrics) {
        this.store = store;
        this.operatorTx = operatorTx;
        this.metrics = metrics;
    }

    private record Done(RuntimeFlag flag, boolean written) {}

    /** @param value a JSON boolean, number or string */
    public RuntimeFlag execute(Caller caller, String key, Object value) {
        return Measured.write(
                        metrics,
                        "flag",
                        done -> done.written() ? WriteMetrics.CREATED : WriteMetrics.IDEMPOTENT,
                        () -> operatorTx.inTransaction(() -> attempt(caller.actor(), key, value)))
                .flag();
    }

    private Done attempt(String actor, String key, Object value) {
        RuntimeFlag current =
                store.find(key).orElseThrow(() -> new NotFoundException("The runtime flag does not exist."));
        if (!current.acceptsType(value)) {
            throw new InvalidFlagValueException("The flag holds a " + RuntimeFlag.typeOf(current.value())
                    + ", so the new value must be a " + RuntimeFlag.typeOf(current.value()) + ".");
        }
        if (current.hasValue(value)) {
            return new Done(current, false);
        }
        RuntimeFlag updated = store.update(key, value, actor)
                .orElseThrow(() -> new NotFoundException("The runtime flag does not exist."));
        log.atInfo()
                .addKeyValue("key", key)
                .addKeyValue("from", current.value())
                .addKeyValue("to", value)
                .addKeyValue("actor", actor)
                .log("runtime flag changed");
        return new Done(updated, true);
    }
}
