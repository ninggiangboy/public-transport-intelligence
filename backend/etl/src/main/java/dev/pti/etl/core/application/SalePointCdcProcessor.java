package dev.pti.etl.core.application;

import dev.pti.etl.core.application.port.BestEffortKeys;
import dev.pti.etl.core.application.port.CdcReader;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.core.domain.InboundMessage;
import dev.pti.etl.core.domain.RuleContext;
import dev.pti.etl.core.domain.SalePointCdc;
import dev.pti.etl.core.domain.SalePointRow;
import dev.pti.etl.core.domain.WriteSet;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Sale point CDC events to {@code dim_sale_point} rows (DOC-20 §1, DOC-14 §8.5). */
public final class SalePointCdcProcessor implements MessageProcessor {

    private final CdcReader reader;
    private final BestEffortKeys keys;

    public SalePointCdcProcessor(CdcReader reader, BestEffortKeys keys) {
        this.reader = reader;
        this.keys = keys;
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
        CdcReader.Read<SalePointCdc> read = reader.salePoint(value);
        SalePointCdc sp = read.value();
        SalePointRow row = new SalePointRow(
                sp.salePointId(), sp.name(), sp.kind(), sp.stopId(), sp.routeId(), sp.isDelete(), sp.lsn());
        return WriteSet.salePoint(message, read.hash(), sp.salePointId(), Instant.ofEpochMilli(sp.sourceTsMs()), row);
    }

    @Override
    public @Nullable String businessKey(InboundMessage message) {
        return keys.of(message, fields -> fields.path("sale_point_id").asString());
    }
}
