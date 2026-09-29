package dev.pti.etl.batch;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import java.time.Duration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Retries a chunk only for {@code TRANSIENT_INFRA} errors, up to {@code maxAttempts} (DOC-19 §4.4). Spring Retry types,
 * because the legacy fault-tolerant builder takes them (DR-80).
 */
public class TransientRetryPolicy extends SimpleRetryPolicy {

    private static final long serialVersionUID = 1L;

    private final transient ErrorClassifier classifier;

    public TransientRetryPolicy(ErrorClassifier classifier, int maxAttempts) {
        super(maxAttempts);
        this.classifier = classifier;
    }

    @Override
    public boolean canRetry(RetryContext context) {
        Throwable last = context.getLastThrowable();
        return (last == null || classifier.classify(last) == ErrorKind.TRANSIENT_INFRA) && super.canRetry(context);
    }

    /** Exponential back-off with jitter: 1 s, 2 s, 4 s, 8 s, 16 s by default (DOC-19 §4.4). */
    public static ExponentialRandomBackOffPolicy backOff(Duration initial, double multiplier, Duration max) {
        ExponentialRandomBackOffPolicy policy = new ExponentialRandomBackOffPolicy();
        policy.setInitialInterval(initial.toMillis());
        policy.setMultiplier(multiplier);
        policy.setMaxInterval(max.toMillis());
        return policy;
    }
}
