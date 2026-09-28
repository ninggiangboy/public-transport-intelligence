package dev.pti.spike.batch;

import org.springframework.batch.core.listener.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the dead letter with the same DataSource, i.e. in whatever transaction Spring Batch has open. */
public class DlqSkipListener implements SkipListener<Item, Item> {

    private final JdbcTemplate jdbc;

    public DlqSkipListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onSkipInWrite(Item item, Throwable t) {
        insert(item.id(), "LOAD");
    }

    @Override
    public void onSkipInProcess(Item item, Throwable t) {
        insert(item.id(), "PROCESS");
    }

    private void insert(int itemId, String stage) {
        jdbc.update(
                "INSERT INTO spike_dlq (item_id, stage, tx_active) VALUES (?, ?, ?)",
                itemId,
                stage,
                TransactionSynchronizationManager.isActualTransactionActive());
    }
}
