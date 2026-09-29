package dev.pti.etl.batch;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;

/**
 * Skips data errors only, and fails the step once more than {@code maxRatio} of the items read were skipped (DR-23,
 * FR-02.7). {@code minSample} keeps a step from failing on one bad item among the first few (DOC-19 §4.4).
 *
 * <p>Stateless: the running step comes from the step thread, so one instance serves every step and job.
 */
public class RatioSkipPolicy implements SkipPolicy {

    private final ErrorClassifier classifier;
    private final double maxRatio;
    private final int minSample;
    private final Supplier<StepExecution> step;

    public RatioSkipPolicy(ErrorClassifier classifier, double maxRatio, int minSample) {
        this(classifier, maxRatio, minSample, StepValues::current);
    }

    RatioSkipPolicy(ErrorClassifier classifier, double maxRatio, int minSample, Supplier<StepExecution> step) {
        this.classifier = classifier;
        this.maxRatio = maxRatio;
        this.minSample = minSample;
        this.step = step;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        if (classifier.classify(t) != ErrorKind.DATA) {
            return false;
        }
        long seen = Math.max(step.get().getReadCount(), minSample);
        double ratio = (double) (skipCount + 1) / seen;
        if (ratio > maxRatio) {
            throw new SkipRatioExceededException(skipCount, seen, maxRatio, t);
        }
        return true;
    }

    /** The step fails with this; its message is the step's exit description. */
    public static final class SkipRatioExceededException extends SkipLimitExceededException {

        private static final long serialVersionUID = 1L;

        private final String message;

        SkipRatioExceededException(long skipCount, long seen, double maxRatio, Throwable cause) {
            super(skipCount, cause);
            this.message = String.format(
                    Locale.ROOT,
                    "Skip ratio %.3f (%d of %d items) is above the limit %.3f",
                    (double) (skipCount + 1) / seen,
                    skipCount + 1,
                    seen,
                    maxRatio);
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
