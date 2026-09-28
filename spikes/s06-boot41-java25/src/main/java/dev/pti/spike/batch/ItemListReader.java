package dev.pti.spike.batch;

import java.util.List;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;

/** Restartable reader: the read count is saved in the step execution context on every commit. */
public class ItemListReader extends AbstractItemCountingItemStreamItemReader<Item> {

    private final List<Item> items;
    private int index;

    public ItemListReader(List<Item> items) {
        this.items = items;
        setName("items");
    }

    @Override
    protected Item doRead() {
        return index < items.size() ? items.get(index++) : null;
    }

    @Override
    protected void jumpToItem(int itemIndex) {
        index = itemIndex;
    }

    @Override
    protected void doOpen() {
        index = 0;
    }

    @Override
    protected void doClose() {}
}
