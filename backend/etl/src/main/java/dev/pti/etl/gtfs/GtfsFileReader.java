package dev.pti.etl.gtfs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.separator.RecordSeparatorPolicy;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.item.file.transform.FieldSet;
import org.springframework.core.io.FileSystemResource;

/**
 * Reads one GTFS file of the job's workspace (DOC-21 §3.2): a {@link FlatFileItemReader} whose column names come
 * from the header line, so the column order does not matter. {@code read.count} is saved in the step context, so
 * a restart continues after the last committed chunk. A missing optional file reads as empty.
 */
public class GtfsFileReader implements ItemStreamReader<GtfsRow> {

    private static final char BOM = '﻿';

    private final GtfsTable table;
    private final FeedWorkspace workspace;
    private @Nullable FlatFileItemReader<GtfsRow> delegate;

    public GtfsFileReader(GtfsTable table, FeedWorkspace workspace) {
        this.table = table;
        this.workspace = workspace;
    }

    @Override
    public void open(ExecutionContext context) {
        JobExecution job = FeedContext.currentJob();
        Path file = workspace.file(job.getJobInstance().getInstanceId(), table);
        if (!Files.exists(file)) {
            delegate = null;
            return;
        }
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setStrict(false);
        FlatFileItemReader<GtfsRow> reader =
                new FlatFileItemReader<>(new FileSystemResource(file), (line, number) -> row(tokenizer, line, number));
        reader.setName(table.stepName());
        reader.setEncoding(StandardCharsets.UTF_8.name());
        reader.setLinesToSkip(1);
        reader.setSkippedLinesCallback(header -> tokenizer.setNames(columns(header)));
        reader.setComments(new String[0]);
        reader.setRecordSeparatorPolicy(new QuotedRecordSeparatorPolicy());
        reader.open(context);
        delegate = reader;
    }

    private GtfsRow row(DelimitedLineTokenizer tokenizer, String line, int number) {
        if (line.isBlank()) {
            return new GtfsRow(table.file(), number, Map.of());
        }
        FieldSet fields = tokenizer.tokenize(line);
        String[] names = fields.getNames();
        String[] values = fields.getValues();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < names.length && i < values.length; i++) {
            map.putIfAbsent(names[i], values[i]);
        }
        return new GtfsRow(table.file(), number, map);
    }

    static String[] columns(String header) {
        String line = !header.isEmpty() && header.charAt(0) == BOM ? header.substring(1) : header;
        List<String> names = List.of(new DelimitedLineTokenizer().tokenize(line).getValues());
        return names.stream().map(String::strip).toArray(String[]::new);
    }

    /** Skips blank lines, which producers leave at the end of files. */
    @Override
    public @Nullable GtfsRow read() throws Exception {
        if (delegate == null) {
            return null;
        }
        GtfsRow row;
        do {
            row = delegate.read();
        } while (row != null && row.blank());
        return row;
    }

    @Override
    public void update(ExecutionContext context) {
        if (delegate != null) {
            delegate.update(context);
        }
    }

    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }

    /** RFC 4180: a quoted field may span lines; the record ends where the quotes are balanced. */
    static final class QuotedRecordSeparatorPolicy implements RecordSeparatorPolicy {

        @Override
        public boolean isEndOfRecord(String record) {
            long quotes = record.chars().filter(c -> c == '"').count();
            return quotes % 2 == 0;
        }

        @Override
        public String postProcess(String record) {
            return record;
        }

        @Override
        public String preProcess(String record) {
            return isEndOfRecord(record) ? record : record + "\n";
        }
    }
}
