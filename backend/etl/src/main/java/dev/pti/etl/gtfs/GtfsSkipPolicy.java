package dev.pti.etl.gtfs;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import dev.pti.common.error.ErrorPhase;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Skips every row error, because the whole feed is judged at {@code validate}, but stops a step after
 * {@code maxRowErrors} of them so a broken feed does not scan for hours (DOC-21 §3.2).
 */
public class GtfsSkipPolicy implements SkipPolicy {

    private final ErrorClassifier classifier;
    private final int maxRowErrors;

    public GtfsSkipPolicy(ErrorClassifier classifier, int maxRowErrors) {
        this.classifier = classifier;
        this.maxRowErrors = maxRowErrors;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        if (!isRowError(t)) {
            return false;
        }
        if (skipCount >= maxRowErrors) {
            throw new TooManyRowErrorsException(maxRowErrors, t);
        }
        return true;
    }

    boolean isRowError(Throwable t) {
        return t instanceof FlatFileParseException
                || t instanceof GtfsRowException
                || classifier.classify(t, ErrorPhase.WRITE) == ErrorKind.DATA;
    }

    /** Its message is the exit description of the failed step. */
    public static final class TooManyRowErrorsException extends SkipLimitExceededException {

        private static final long serialVersionUID = 1L;

        TooManyRowErrorsException(int limit, Throwable cause) {
            super(limit, cause);
        }

        @Override
        public String getMessage() {
            return "More than " + getSkipLimit()
                    + " row errors; the feed looks broken (pti.gtfs.static.max-row-errors)";
        }
    }
}
