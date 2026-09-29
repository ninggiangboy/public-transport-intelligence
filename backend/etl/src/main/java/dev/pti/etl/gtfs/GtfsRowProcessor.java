package dev.pti.etl.gtfs;

import org.springframework.batch.infrastructure.item.ItemProcessor;

/** Maps a row to its INSERT for the STAGED version of the running job (DOC-13 §2.4). Pure, so a scan may repeat it. */
public class GtfsRowProcessor implements ItemProcessor<GtfsRow, GtfsInsert> {

    private final GtfsTable table;

    public GtfsRowProcessor(GtfsTable table) {
        this.table = table;
    }

    @Override
    public GtfsInsert process(GtfsRow row) {
        return table.map(row, table == GtfsTable.VEHICLES ? 0 : FeedContext.feedVersionId(FeedContext.currentJob()));
    }
}
