package dev.pti.etl.gtfs;

import dev.pti.common.error.FatalException;
import dev.pti.etl.raw.RawZone;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * Makes sure the extracted feed is there before a step reads it (DOC-21 §3.1, test G-07): a restart may run on
 * another pod or after {@code /tmp} was cleared. The zip comes back from the raw zone and its hash is checked.
 */
public class FeedWorkspaceListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(FeedWorkspaceListener.class);

    /** Written after a complete extraction, so a half-extracted directory is not trusted. */
    static final String MARKER = ".extracted";

    private final FeedWorkspace workspace;
    private final FeedExtractor extractor;
    private final RawZone raw;

    public FeedWorkspaceListener(FeedWorkspace workspace, FeedExtractor extractor, RawZone raw) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.raw = raw;
    }

    @Override
    public void beforeStep(StepExecution step) {
        JobExecution job = step.getJobExecution();
        long instance = job.getJobInstance().getInstanceId();
        if (Files.exists(workspace.files(instance).resolve(MARKER))) {
            return;
        }
        String hash = FeedContext.feedHash(job);
        log.info("Workspace of feed {} is missing; downloading it again from the raw zone", hash);
        Path zip = workspace.zip(instance);
        raw.download(raw.key(FeedSource.RAW_PREFIX + hash + ".zip"), zip);
        String actual = FeedSource.sha256(zip);
        if (!actual.equals(hash)) {
            throw new FatalException("Raw zone copy of feed " + hash + " has hash " + actual);
        }
        extract(extractor, zip, workspace.files(instance));
    }

    /** Extracts and marks the directory as complete. */
    static FeedExtractor.Extracted extract(FeedExtractor extractor, Path zip, Path target) {
        FeedExtractor.Extracted extracted = extractor.extract(zip, target);
        try {
            Files.writeString(target.resolve(MARKER), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return extracted;
    }
}
