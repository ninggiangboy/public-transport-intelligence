package dev.pti.etl.core.cdc;

import dev.pti.common.message.PayloadHasher;
import dev.pti.etl.core.BestEffortKeys;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.SalePointRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RuleContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Sale point CDC events to {@code dim_sale_point} rows (DOC-20 §1, DOC-14 §8.5). */
public final class SalePointCdcProcessor implements MessageProcessor {

    private final CdcReader reader;

    public SalePointCdcProcessor(CdcReader reader) {
        this.reader = reader;
    }

    @Override
    public EtlSource source() {
        return EtlSource.TICKETING_SALE_POINTS;
    }

    @Override
    public WriteSet process(InboundMessage message, RuleContext context) {
        byte[] value = message.value();
        if (value == null) {
            return WriteSet.empty(message);
        }
        CdcReader.Read<SalePointCdc> read = reader.read(value, SalePointCdc.class);
        SalePointCdc sp = read.value();
        SalePointRow row = new SalePointRow(
                sp.salePointId(), sp.name(), sp.kind(), sp.stopId(), sp.routeId(), sp.isDelete(), sp.lsn());
        return WriteSet.salePoint(
                message,
                PayloadHasher.hashCdc(read.tree()),
                sp.salePointId(),
                Instant.ofEpochMilli(sp.sourceTsMs()),
                row);
    }

    @Override
    public @Nullable String businessKey(InboundMessage message) {
        return BestEffortKeys.of(message, tree -> tree.path("sale_point_id").asString(null));
    }
}
