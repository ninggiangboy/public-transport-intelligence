package dev.pti.etl.batch;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.rules.RuleContext;
import java.util.function.Supplier;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Routes each item to the processor of its source (DOC-19 §4.2). A replay reads several sources in one step, and the
 * {@code replay} job parameter relaxes the rules that do not apply to old data (DOC-16 §5).
 */
public class MessageProcessorRouter implements ItemProcessor<InboundMessage, WriteSet> {

    private final MessageProcessors processors;
    private final BusinessClock clock;
    private final ReferenceDataHolder reference;
    private final Supplier<StepExecution> step;

    public MessageProcessorRouter(MessageProcessors processors, BusinessClock clock, ReferenceDataHolder reference) {
        this(processors, clock, reference, StepValues::current);
    }

    MessageProcessorRouter(
            MessageProcessors processors,
            BusinessClock clock,
            ReferenceDataHolder reference,
            Supplier<StepExecution> step) {
        this.processors = processors;
        this.clock = clock;
        this.reference = reference;
        this.step = step;
    }

    @Override
    public WriteSet process(InboundMessage item) {
        RuleContext context = new RuleContext(
                clock.instant(),
                clock.offset(),
                StepValues.replay(step.get()),
                reference.current().orElse(null));
        return processors.forSource(item.source()).process(item, context);
    }
}
